package org.receiverman.domains;

import java.util.List;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.descriptors.functional.Intension;
import org.receiverman.descriptors.functional.Intensions;
import org.receiverman.descriptors.predicates.Become;

public final class ScenarioCompiler {
  public CompiledScenario compile(Supplier supplier, SupplierScenario scenario) {
    return new CompiledScenario(supplier, scenario, scenario.conditions());
  }

  public Intension<ParsedEvent, FulfillmentToken> compileIntension(
      Supplier supplier,
      SupplierScenario scenario
  ) {
    List<Condition> conditions = scenario.conditions();
    Intension<ParsedEvent, FulfillmentToken> compiled = compileStep(supplier, scenario, conditions.getFirst());

    for (int i = 1; i < conditions.size(); i++) {
      Condition next = conditions.get(i);
      compiled = Intensions.then(compiled, ignored -> compileStep(supplier, scenario, next));
    }

    return compiled;
  }

  private Intension<ParsedEvent, FulfillmentToken> compileStep(
      Supplier supplier,
      SupplierScenario scenario,
      Condition condition
  ) {
    return Intensions.map(
        Become.firstMatch(event -> condition.matches(event, event.receivedAt()), condition.timeout()),
        event -> condition.renderFulfillment(supplier, scenario, event)
    );
  }
}
