package org.recieverman.domains;

import java.util.List;

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
