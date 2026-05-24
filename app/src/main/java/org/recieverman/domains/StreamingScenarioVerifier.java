package org.recieverman.domains;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.recieverman.descriptors.entities.ParsedEvent;

public final class StreamingScenarioVerifier {
  private final Supplier supplier;
  private final SupplierScenario scenario;
  private final Clock clock;
  private int nextConditionIndex;
  private Instant cursor;
  private boolean failed;

  public StreamingScenarioVerifier(Supplier supplier, SupplierScenario scenario, Clock clock) {
    this.supplier = supplier;
    this.scenario = scenario;
    this.clock = clock;
    this.cursor = clock.instant();
  }

  public Optional<FulfillmentToken> accept(ParsedEvent event) {
    if (failed || complete()) {
      return Optional.empty();
    }

    Condition condition = scenario.conditions().get(nextConditionIndex);
    if (event.receivedAt().isAfter(cursor.plus(condition.timeout()))) {
      failed = true;
      return Optional.empty();
    }
    if (!condition.matches(event, clock.instant())) {
      return Optional.empty();
    }

    cursor = event.receivedAt();
    nextConditionIndex++;
    return Optional.of(new FulfillmentToken(
        supplier,
        scenario,
        condition,
        event,
        condition.renderToken(event)
    ));
  }

  public boolean complete() {
    return nextConditionIndex >= scenario.conditions().size();
  }

  public boolean failed() {
    return failed;
  }

  public Optional<Duration> remaining() {
    if (failed || complete()) {
      return Optional.empty();
    }
    Condition condition = scenario.conditions().get(nextConditionIndex);
    Duration remaining = Duration.between(clock.instant(), cursor.plus(condition.timeout()));
    return Optional.of(remaining.isNegative() ? Duration.ZERO : remaining);
  }
}
