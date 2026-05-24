package org.receiverman.domains;

import java.time.Clock;
import java.util.List;

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
