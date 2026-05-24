package org.receiverman.domains.ingress;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.supplier.SupplierTemplate;

public final class ReceiverInput {
  private ReceiverInput() {}

  public static ParsedEvent parse(
      SupplierTemplate template,
      ReceiverRegistry registry,
      Clock clock,
      String receiverId,
      String raw
  ) {
    ReceiverSpec spec = template.receiver(receiverId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown receiver: " + receiverId));
    ParsedEvent parsed = registry.requireParser(spec.parser()).parse(raw, Instant.now(clock));
    Map<String, String> fields = new LinkedHashMap<>(parsed.fields());
    fields.put("receiver", spec.id());
    fields.put("parser", spec.parser());
    return ParsedEvent.of(parsed.raw(), parsed.receivedAt(), fields);
  }
}
