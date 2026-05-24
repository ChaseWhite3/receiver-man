package org.receiverman.domains.fulfillment;

import java.util.List;

import org.receiverman.domains.scenario.Condition;
import org.receiverman.domains.scenario.SupplierScenario;
import org.receiverman.domains.supplier.Supplier;

public record FulfillmentReport(
    Supplier supplier,
    SupplierScenario scenario,
    List<ConditionResult> results
) {
  public FulfillmentReport {
    results = List.copyOf(results);
  }

  public boolean fulfilled() {
    return results.stream().allMatch(ConditionResult::fulfilled);
  }

  public record ConditionResult(
      Condition condition,
      boolean fulfilled,
      String detail
  ) {}
}
