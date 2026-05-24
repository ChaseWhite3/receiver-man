package org.recieverman.descriptors.entities;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Our emitted events (simulated) as seen by the receiver abstraction. */
public record Event(String raw, Instant receivedAt) {
  public String msgType() {
    for (String token : raw.split("\\s+")) {
      int separator = token.indexOf('=');
      if (separator > 0 && "type".equals(token.substring(0, separator))) {
        return token.substring(separator + 1);
      }
    }
    int i = raw.indexOf('|');
    return (i >= 0) ? raw.substring(0, i) : raw;
  }

  public ParsedEvent parsed() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("type", msgType());
    fields.put("raw", raw);
    return ParsedEvent.of(raw, receivedAt, fields);
  }
}
