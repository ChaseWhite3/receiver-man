package org.receiverman.domains;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.receiverman.descriptors.World;
import org.receiverman.descriptors.entities.EventBus;
import org.receiverman.descriptors.entities.ParsedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReceiverManBus {
  private static final Logger LOG = LoggerFactory.getLogger(ReceiverManBus.class);

  private final SupplierTemplate template;
  private final ReceiverRegistry registry;
  private final Clock clock;
  private final EventBus<ParsedEvent> events = new EventBus<>();
  private final ScenarioCompiler compiler = new ScenarioCompiler();
  private final CopyOnWriteArrayList<ParsedEvent> acceptedEvents = new CopyOnWriteArrayList<>();

  public ReceiverManBus(SupplierTemplate template, ReceiverRegistry registry, Clock clock) {
    this.template = template;
    this.registry = registry;
    this.clock = clock;
  }

  public static ReceiverManBus fromTemplate(
      SupplierTemplate template,
      ReceiverRegistry registry,
      Clock clock
  ) {
    return new ReceiverManBus(template, registry, clock);
  }

  public static ReceiverManBuilder builder() {
    return new ReceiverManBuilder();
  }

  /** Await the first (and typically only) scenario. */
  public CompletionStage<FulfillmentToken> await() {
    return doAwait(findScenario(null), token -> {});
  }

  /** Await a named scenario. */
  public CompletionStage<FulfillmentToken> await(String scenarioName) {
    return doAwait(findScenario(scenarioName), token -> {});
  }

  /**
   * Await the first scenario, calling {@code onStep} as each step is fulfilled
   * (including the final step).
   */
  public CompletionStage<FulfillmentToken> await(Consumer<FulfillmentToken> onStep) {
    return doAwait(findScenario(null), onStep);
  }

  /**
   * Await a named scenario, calling {@code onStep} as each step is fulfilled
   * (including the final step).
   */
  public CompletionStage<FulfillmentToken> await(String scenarioName, Consumer<FulfillmentToken> onStep) {
    return doAwait(findScenario(scenarioName), onStep);
  }

  /**
   * Await the first scenario and collect every fulfilled step token into an ordered list.
   * The list contains one entry per scenario step, in fulfillment order, including the final step.
   */
  public CompletionStage<List<FulfillmentToken>> awaitAll() {
    return awaitAll((String) null);
  }

  /**
   * Await a named scenario and collect every fulfilled step token into an ordered list.
   */
  public CompletionStage<List<FulfillmentToken>> awaitAll(String scenarioName) {
    List<FulfillmentToken> steps = new ArrayList<>();
    return doAwait(findScenario(scenarioName), steps::add)
        .thenApply(ignored -> List.copyOf(steps));
  }

  // --- Blocking convenience overloads (no CompletionStage required) ----------

  /**
   * Block until the first scenario is fulfilled and return its token, or throw if it
   * does not complete within {@code timeout}.
   *
   * <pre>{@code
   * FulfillmentToken token = bus.await(Duration.ofSeconds(10));
   * }</pre>
   *
   * @throws ReceiverManExpectationException if the scenario fails, times out, or is interrupted.
   */
  public FulfillmentToken await(Duration timeout) {
    return block(await(), timeout);
  }

  /**
   * Block until the named scenario is fulfilled and return its token, or throw if it
   * does not complete within {@code timeout}.
   *
   * @throws ReceiverManExpectationException if the scenario fails, times out, or is interrupted.
   */
  public FulfillmentToken await(String scenarioName, Duration timeout) {
    return block(await(scenarioName), timeout);
  }

  /**
   * Block until the first scenario is fulfilled and return the ordered list of all step tokens,
   * or throw if it does not complete within {@code timeout}.
   *
   * <pre>{@code
   * List<FulfillmentToken> trace = bus.awaitAll(Duration.ofSeconds(10));
   * trace.forEach(t -> LOG.info("step: {} → {}", t.name(), t.value()));
   * }</pre>
   *
   * @throws ReceiverManExpectationException if the scenario fails, times out, or is interrupted.
   */
  public List<FulfillmentToken> awaitAll(Duration timeout) {
    return block(awaitAll(), timeout);
  }

  /**
   * Block until the named scenario is fulfilled and return the ordered list of all step tokens,
   * or throw if it does not complete within {@code timeout}.
   *
   * @throws ReceiverManExpectationException if the scenario fails, times out, or is interrupted.
   */
  public List<FulfillmentToken> awaitAll(String scenarioName, Duration timeout) {
    return block(awaitAll(scenarioName), timeout);
  }

  /**
   * Block on a {@link CompletionStage} that was obtained from an earlier {@code await*()} call,
   * waiting up to {@code timeout}. Use this when you need to subscribe before the action that
   * triggers events, then collect results afterwards:
   *
   * <pre>{@code
   * var pending = bus.awaitAll();          // subscribe first
   * triggerSomethingThatSendsEvents();
   * var steps = bus.await(pending, Duration.ofSeconds(10)); // block after
   * }</pre>
   *
   * @throws ReceiverManExpectationException if the stage fails, times out, or is interrupted.
   */
  public <T> T await(CompletionStage<T> pending, Duration timeout) {
    return block(pending, timeout);
  }

  private <T> T block(CompletionStage<T> stage, Duration timeout) {
    try {
      return stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ReceiverManExpectationException rex) throw rex;
      throw new ReceiverManExpectationException("Scenario failed: " + cause.getMessage(), cause);
    } catch (TimeoutException e) {
      throw new ReceiverManExpectationException(
          "Scenario did not complete within " + timeout, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ReceiverManExpectationException("Interrupted while awaiting scenario", e);
    }
  }

  /** @deprecated Use {@link #await()} instead. */
  @Deprecated
  public CompletionStage<FulfillmentToken> awaitFirstScenario() {
    return await();
  }

  private CompletionStage<FulfillmentToken> doAwait(
      SupplierScenario scenario,
      Consumer<FulfillmentToken> onStep
  ) {
    return compiler.compileIntension(template.supplier(), scenario, onStep)
        .run(new World(clock), events)
        .thenApply(finalToken -> {
          onStep.accept(finalToken);
          return finalToken;
        })
        .exceptionallyCompose(error -> {
          String diagnostic = describeExpectationFailure(scenario, error);
          LOG.error("ReceiverMan expectation failed for supplier='{}', scenario='{}', routeField='{}'.{}{}",
              template.supplier().name(),
              scenario.name(),
              template.routeBy(),
              System.lineSeparator(),
              diagnostic,
              error);
          throw new CompletionException(new ReceiverManExpectationException(diagnostic, error));
        });
  }

  public ParsedEvent accept(String receiverId, String raw) {
    ParsedEvent event = ReceiverInput.parse(template, registry, clock, receiverId, raw);
    acceptedEvents.add(event);
    LOG.debug(
        "ReceiverMan accepted message from receiver='{}', parser='{}', receivedAt='{}', fields={}, raw='{}'",
        event.field("receiver").orElse("<missing>"),
        event.field("parser").orElse("<missing>"),
        event.receivedAt(),
        event.fields(),
        event.raw()
    );
    events.emit(event);
    return event;
  }

  public EventBus<ParsedEvent> events() {
    return events;
  }

  /**
   * Start tracking a series of extracted values from all events on this bus.
   * Call before sending events to capture everything from the start.
   *
   * <pre>{@code
   * ValueSeries<Double> temps = bus.track(
   *     event -> event.field("obx.temperature").map(Double::parseDouble)
   * );
   * }</pre>
   *
   * @param extractor returns the value to record, or {@link Optional#empty()} to skip the event
   */
  public <T extends Comparable<T>> ValueSeries<T> track(
      Function<ParsedEvent, Optional<T>> extractor
  ) {
    return new ValueSeries<>(events, extractor);
  }

  /**
   * Start tracking a series of extracted values from events arriving on a specific receiver.
   * Events from other receivers are silently skipped.
   *
   * <pre>{@code
   * ValueSeries<Double> temps = bus.track(
   *     "vitals",
   *     event -> event.field("obx.temperature").map(Double::parseDouble)
   * );
   * }</pre>
   *
   * @param receiverId only events whose {@code receiver} field equals this id are considered
   * @param extractor  returns the value to record, or {@link Optional#empty()} to skip the event
   */
  public <T extends Comparable<T>> ValueSeries<T> track(
      String receiverId,
      Function<ParsedEvent, Optional<T>> extractor
  ) {
    return new ValueSeries<>(events, event ->
        event.field("receiver").filter(receiverId::equals).isPresent()
            ? extractor.apply(event)
            : Optional.empty()
    );
  }

  private SupplierScenario findScenario(String scenarioName) {
    if (scenarioName == null || scenarioName.isBlank()) {
      return template.supplier().scenarios().getFirst();
    }
    Optional<SupplierScenario> found = template.supplier().scenarios().stream()
        .filter(scenario -> scenario.name().equals(scenarioName))
        .findFirst();
    return found.orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioName));
  }

  private String describeExpectationFailure(SupplierScenario scenario, Throwable error) {
    StringBuilder message = new StringBuilder();
    message.append("ReceiverMan expectation failed.");
    message.append(System.lineSeparator()).append("Supplier: ").append(template.supplier().name());
    message.append(System.lineSeparator()).append("Scenario: ").append(scenario.name());
    message.append(System.lineSeparator()).append("Route field: ").append(template.routeBy());
    message.append(System.lineSeparator()).append("Cause: ").append(rootCause(error).getMessage());
    message.append(System.lineSeparator()).append("Expected steps:");
    for (Condition condition : scenario.conditions()) {
      message.append(System.lineSeparator()).append("- ")
          .append(condition.name())
          .append(" from ")
          .append(condition.receiver() == null ? "<any receiver>" : condition.receiver())
          .append(" expecting ")
          .append(condition.describeAssertions());
    }

    List<ParsedEvent> recent = acceptedEvents.stream()
        .skip(Math.max(0, acceptedEvents.size() - 5))
        .toList();
    if (recent.isEmpty()) {
      message.append(System.lineSeparator()).append("No messages were accepted by ReceiverMan.");
      return message.toString();
    }

    message.append(System.lineSeparator()).append("Recent accepted messages:");
    for (ParsedEvent event : recent) {
      message.append(System.lineSeparator()).append("- receiver=")
          .append(event.field("receiver").orElse("<missing>"))
          .append(", parser=")
          .append(event.field("parser").orElse("<missing>"))
          .append(", receivedAt=")
          .append(event.receivedAt());
      message.append(System.lineSeparator()).append("  fields=").append(event.fields());
      message.append(System.lineSeparator()).append("  raw=").append(event.raw());
      for (Condition condition : scenario.conditions()) {
        if (!condition.matches(event, clock.instant())) {
          message.append(System.lineSeparator()).append("  mismatch for ")
              .append(condition.name()).append(":")
              .append(System.lineSeparator())
              .append(indent(condition.diagnose(event, clock.instant()), "    "));
        }
      }
    }
    return message.toString();
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String indent(String value, String prefix) {
    return prefix + value.replace(System.lineSeparator(), System.lineSeparator() + prefix);
  }
}
