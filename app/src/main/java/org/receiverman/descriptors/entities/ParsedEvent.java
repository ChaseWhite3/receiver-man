package org.receiverman.descriptors.entities;

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

  public ParsedEvent withField(String name, String value) {
    Map<String, String> updated = new LinkedHashMap<>(fields);
    updated.put(name, value);
    return ParsedEvent.of(raw, receivedAt, updated);
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
