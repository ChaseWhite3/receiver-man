package org.recieverman.domains;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.recieverman.descriptors.entities.ParsedEvent;

public final class ScenarioRouter {
  private final Supplier supplier;
  private final SupplierScenario scenario;
  private final String routeField;
  private final Clock clock;
  private final Map<String, StreamingScenarioVerifier> verifiers = new ConcurrentHashMap<>();

  public ScenarioRouter(Supplier supplier, SupplierScenario scenario, String routeField, Clock clock) {
    if (routeField == null || routeField.isBlank()) {
      throw new IllegalArgumentException("routeField must not be blank");
    }
    this.supplier = supplier;
    this.scenario = scenario;
    this.routeField = routeField.trim();
    this.clock = clock;
  }

  public Optional<RoutedFulfillment> route(ParsedEvent event) {
    Optional<String> maybeKey = event.field(routeField).filter(value -> !value.isBlank());
    if (maybeKey.isEmpty()) {
      return Optional.empty();
    }

    String routeKey = maybeKey.get();
    StreamingScenarioVerifier verifier = verifiers.computeIfAbsent(
        routeKey,
        ignored -> new StreamingScenarioVerifier(supplier, scenario, clock)
    );

    synchronized (verifier) {
      return verifier.accept(event)
          .map(token -> new RoutedFulfillment(routeKey, token));
    }
  }

  public Optional<StreamingScenarioVerifier> verifier(String routeKey) {
    return Optional.ofNullable(verifiers.get(routeKey));
  }

  public int activeRouteCount() {
    return verifiers.size();
  }

  public String routeField() {
    return routeField;
  }
}
