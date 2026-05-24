package org.receiverman.domains.routing;

import java.time.Clock;
import java.util.List;

import org.receiverman.domains.scenario.Condition;
import org.receiverman.domains.scenario.SupplierScenario;
import org.receiverman.domains.supplier.Supplier;

public record CompiledScenario(Supplier supplier, SupplierScenario scenario, List<Condition> steps) {
  public CompiledScenario {
    steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("steps must not be empty");
    }
  }

  public ScenarioRun start(Clock clock) {
    return new ScenarioRun(this, clock);
  }
}
