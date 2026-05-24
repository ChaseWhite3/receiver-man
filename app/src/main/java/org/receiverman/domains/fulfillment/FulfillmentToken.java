package org.receiverman.domains.fulfillment;

import java.util.Map;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.scenario.Condition;
import org.receiverman.domains.scenario.SupplierScenario;
import org.receiverman.domains.supplier.Supplier;

public record FulfillmentToken(
    Supplier supplier,
    SupplierScenario scenario,
    Condition condition,
    ParsedEvent event,
    String name,
    String value,
    Map<String, String> payload,
    Map<String, String> effects
) {
  public FulfillmentToken {
    payload = Map.copyOf(payload);
    effects = Map.copyOf(effects);
  }

  public String token() {
    return value;
  }
}
