package org.recieverman.domains;

import org.recieverman.descriptors.entities.ParsedEvent;

public record FulfillmentToken(
    Supplier supplier,
    SupplierScenario scenario,
    Condition condition,
    ParsedEvent event,
    String token
) {}
