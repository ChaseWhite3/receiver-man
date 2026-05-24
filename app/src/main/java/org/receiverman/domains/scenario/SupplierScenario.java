package org.receiverman.domains.scenario;

import java.util.List;

public record SupplierScenario(String name, List<Condition> conditions) {
  public SupplierScenario {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    conditions = List.copyOf(conditions);
    if (conditions.isEmpty()) {
      throw new IllegalArgumentException("conditions must not be empty");
    }
  }
}
