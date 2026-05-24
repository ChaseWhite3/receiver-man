package org.receiverman.domains.routing;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.fulfillment.FulfillmentToken;
import org.receiverman.domains.scenario.SupplierScenario;
import org.receiverman.domains.supplier.Supplier;

public final class StreamingScenarioVerifier {
  private final ScenarioRun run;

  public StreamingScenarioVerifier(Supplier supplier, SupplierScenario scenario, Clock clock) {
    this(new ScenarioCompiler().compile(supplier, scenario), clock);
  }

  public StreamingScenarioVerifier(CompiledScenario compiledScenario, Clock clock) {
    this.run = compiledScenario.start(clock);
  }

  public Optional<FulfillmentToken> accept(ParsedEvent event) {
    return run.accept(event);
  }

  public boolean complete() {
    return run.complete();
  }

  public boolean failed() {
    return run.failed();
  }

  public Optional<Duration> remaining() {
    return run.remaining();
  }
}
