package org.receiverman.domains;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScenarioRun {
  private static final Logger LOG = LoggerFactory.getLogger(ScenarioRun.class);

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
    return acceptResult(event).tokenOptional();
  }

  public AcceptResult acceptResult(ParsedEvent event) {
    if (failed || complete()) {
      return AcceptResult.ignored();
    }

    Condition step = compiledScenario.steps().get(nextStepIndex);
    if (event.receivedAt().isAfter(cursor.plus(step.timeout()))) {
      failed = true;
      String diagnostic = step.diagnose(event, clock.instant());
      LOG.warn(
          "ReceiverMan scenario step timed out. supplier='{}', scenario='{}', step='{}'.{}{}",
          compiledScenario.supplier().name(),
          compiledScenario.scenario().name(),
          step.name(),
          System.lineSeparator(),
          diagnostic
      );
      return AcceptResult.failed(diagnostic);
    }
    if (!step.matches(event, clock.instant())) {
      String diagnostic = step.diagnose(event, clock.instant());
      LOG.debug(
          "ReceiverMan scenario step not fulfilled yet. supplier='{}', scenario='{}', step='{}'.{}{}",
          compiledScenario.supplier().name(),
          compiledScenario.scenario().name(),
          step.name(),
          System.lineSeparator(),
          diagnostic
      );
      return AcceptResult.waiting(diagnostic);
    }

    cursor = event.receivedAt();
    nextStepIndex++;
    FulfillmentToken token = step.renderFulfillment(compiledScenario.supplier(), compiledScenario.scenario(), event);
    LOG.info(
        "ReceiverMan fulfilled step. supplier='{}', scenario='{}', step='{}', tokenName='{}', tokenValue='{}'",
        compiledScenario.supplier().name(),
        compiledScenario.scenario().name(),
        step.name(),
        token.name(),
        token.value()
    );
    return complete() ? AcceptResult.completed(token) : AcceptResult.advanced(token);
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
