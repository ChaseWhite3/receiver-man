package org.receiverman.domains;

import java.util.Map;

import org.receiverman.descriptors.entities.ParsedEvent;

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
