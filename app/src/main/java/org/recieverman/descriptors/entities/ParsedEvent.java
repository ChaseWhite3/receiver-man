package org.recieverman.descriptors.entities;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record ParsedEvent(String raw, Instant receivedAt, Map<String, String> fields) {
  public ParsedEvent {
    fields = Map.copyOf(fields);
  }

  public Optional<String> field(String name) {
    String key = name == null ? "" : name.trim();
    return Optional.ofNullable(fields.get(key));
  }

  public static ParsedEvent of(String raw, Instant receivedAt, Map<String, String> fields) {
    Map<String, String> normalized = new LinkedHashMap<>();
    fields.forEach((key, value) -> {
      if (key != null && !key.isBlank() && value != null) {
        normalized.put(key.trim(), value.trim());
      }
    });
    return new ParsedEvent(raw, receivedAt, normalized);
  }
}
