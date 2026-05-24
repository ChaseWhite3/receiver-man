package org.recieverman.domains;

import java.util.List;

public record Supplier(String name, List<SupplierScenario> scenarios) {
  public Supplier {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    scenarios = List.copyOf(scenarios);
  }
}
