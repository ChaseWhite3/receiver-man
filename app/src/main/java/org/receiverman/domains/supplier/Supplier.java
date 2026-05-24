package org.receiverman.domains.supplier;

import java.util.List;

import org.receiverman.domains.scenario.SupplierScenario;

public record Supplier(String name, List<SupplierScenario> scenarios) {
  public Supplier {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    scenarios = List.copyOf(scenarios);
  }
}
