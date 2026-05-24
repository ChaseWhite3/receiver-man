package org.receiverman.domains;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

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

  public CompletionStage<FulfillmentToken> await(String scenarioName) {
    SupplierScenario scenario = findScenario(scenarioName);
    return compiler.compileIntension(template.supplier(), scenario)
        .run(new World(clock), events)
        .exceptionallyCompose(error -> {
          String diagnostic = describeExpectationFailure(scenario, error);
          LOG.error("ReceiverMan expectation failed for supplier='{}', scenario='{}', routeField='{}'.{}{}",
              template.supplier().name(),
              scenario.name(),
              template.routeBy(),
              System.lineSeparator(),
              diagnostic,
              error);
          throw new CompletionException(new ReceiverManExpectationException(
              diagnostic,
              error
          ));
        });
  }

  public CompletionStage<FulfillmentToken> awaitFirstScenario() {
    return compiler.compileIntension(template.supplier(), template.supplier().scenarios().getFirst())
        .run(new World(clock), events);
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
