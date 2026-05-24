package org.receiverman.domains;

import java.time.Clock;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.descriptors.functional.Intension;

public final class ReceiverManIntensions {
  private ReceiverManIntensions() {}

  public static ScenarioBuilder scenario(String name) {
    return new ScenarioBuilder(name);
  }

  public static ScenarioRun compile(Supplier supplier, SupplierScenario scenario, Clock clock) {
    return new ScenarioCompiler().compile(supplier, scenario).start(clock);
  }

  public static Intension<ParsedEvent, FulfillmentToken> compileIntension(
      Supplier supplier,
      SupplierScenario scenario
  ) {
    return new ScenarioCompiler().compileIntension(supplier, scenario);
  }
}
