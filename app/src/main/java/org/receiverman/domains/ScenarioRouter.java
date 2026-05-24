package org.receiverman.domains;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.receiverman.descriptors.entities.ParsedEvent;


/**
 * Routes events to compiled scenario runs based on a correlation field.
 */
public final class ScenarioRouter {
  private final CompiledScenario compiledScenario;
  private final String routeField;
  private final Clock clock;
  private final Map<String, ScenarioRun> runs = new ConcurrentHashMap<>();

  public ScenarioRouter(Supplier supplier, SupplierScenario scenario, String routeField, Clock clock) {
    this(new ScenarioCompiler().compile(supplier, scenario), routeField, clock);
  }

  public ScenarioRouter(CompiledScenario compiledScenario, String routeField, Clock clock) {
    if (routeField == null || routeField.isBlank()) {
      throw new IllegalArgumentException("routeField must not be blank");
    }
    this.compiledScenario = compiledScenario;
    this.routeField = routeField.trim();
    this.clock = clock;
  }

  public Optional<RoutedFulfillment> route(ParsedEvent event) {
    return routeResult(event).fulfillment();
  }

  public RoutedAcceptResult routeResult(ParsedEvent event) {
    Optional<String> maybeKey = event.field(routeField).filter(value -> !value.isBlank());
    if (maybeKey.isEmpty()) {
      return new RoutedAcceptResult("", AcceptResult.ignored());
    }

    String routeKey = maybeKey.get();
    ScenarioRun run = runs.computeIfAbsent(
        routeKey,
        ignored -> compiledScenario.start(clock)
    );

    synchronized (run) {
      return new RoutedAcceptResult(routeKey, run.acceptResult(event));
    }
  }

  public Optional<ScenarioRun> run(String routeKey) {
    return Optional.ofNullable(runs.get(routeKey));
  }

  public int activeRouteCount() {
    return runs.size();
  }

  public String routeField() {
    return routeField;
  }

  public CompiledScenario compiledScenario() {
    return compiledScenario;
  }
}
