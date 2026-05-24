package org.receiverman.domains;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.receiverman.descriptors.entities.ParsedEvent;

public final class ScenarioRun {
  private final CompiledScenario compiledScenario;
  private final Clock clock;
  private int nextStepIndex;
  private Instant cursor;
  private boolean failed;

  ScenarioRun(CompiledScenario compiledScenario, Clock clock) {
    this.compiledScenario = compiledScenario;
    this.clock = clock;
    this.cursor = clock.instant();
  }

  public Optional<FulfillmentToken> accept(ParsedEvent event) {
    if (failed || complete()) {
      return Optional.empty();
    }

    Condition step = compiledScenario.steps().get(nextStepIndex);
    if (event.receivedAt().isAfter(cursor.plus(step.timeout()))) {
      failed = true;
      return Optional.empty();
    }
    if (!step.matches(event, clock.instant())) {
      return Optional.empty();
    }

    cursor = event.receivedAt();
    nextStepIndex++;
    return Optional.of(step.renderFulfillment(compiledScenario.supplier(), compiledScenario.scenario(), event));
  }

  public boolean complete() {
    return nextStepIndex >= compiledScenario.steps().size();
  }

  public boolean failed() {
    return failed;
  }

  public Optional<Duration> remaining() {
    if (failed || complete()) {
      return Optional.empty();
    }
    Condition step = compiledScenario.steps().get(nextStepIndex);
    Duration remaining = Duration.between(clock.instant(), cursor.plus(step.timeout()));
    return Optional.of(remaining.isNegative() ? Duration.ZERO : remaining);
  }

  public CompiledScenario compiledScenario() {
    return compiledScenario;
  }
}
