package org.recieverman.domains;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.recieverman.descriptors.entities.ParsedEvent;

public final class DefaultEventParser implements EventParser {
  @Override
  public ParsedEvent parse(String raw, Instant receivedAt) {
    String trimmed = raw == null ? "" : raw.trim();
    Map<String, String> fields = new LinkedHashMap<>();

    if (looksLikeFlatJson(trimmed)) {
      parseFlatJson(trimmed, fields);
    } else {
      parseKeyValuePairs(trimmed, fields);
    }

    if (!fields.containsKey("type")) {
      String type = parseLegacyType(trimmed);
      if (!type.isBlank()) {
        fields.put("type", type);
        fields.putIfAbsent("msh.messageType", type);
      }
    }
    if (fields.containsKey("type")) {
      fields.putIfAbsent("msh.messageType", fields.get("type"));
    }
    if (fields.containsKey("patientId")) {
      fields.putIfAbsent("pid.patientId", fields.get("patientId"));
    }
    if (fields.containsKey("orderId")) {
      fields.putIfAbsent("obr.placerOrderNumber", fields.get("orderId"));
    }
    fields.putIfAbsent("raw", trimmed);
    return ParsedEvent.of(trimmed, receivedAt, fields);
  }

  private static boolean looksLikeFlatJson(String raw) {
    return raw.startsWith("{") && raw.endsWith("}");
  }

  private static void parseFlatJson(String raw, Map<String, String> fields) {
    String body = raw.substring(1, raw.length() - 1).trim();
    if (body.isEmpty()) return;

    for (String part : body.split(",")) {
      int separator = part.indexOf(':');
      if (separator < 0) continue;
      String key = cleanJson(part.substring(0, separator));
      String value = cleanJson(part.substring(separator + 1));
      if (!key.isBlank()) {
        fields.put(key, value);
      }
    }
  }

  private static String cleanJson(String value) {
    String cleaned = value.trim();
    if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
      return cleaned.substring(1, cleaned.length() - 1);
    }
    return cleaned;
  }

  private static void parseKeyValuePairs(String raw, Map<String, String> fields) {
    for (String token : raw.split("\\s+")) {
      int separator = token.indexOf('=');
      if (separator < 1) continue;
      fields.put(token.substring(0, separator), token.substring(separator + 1));
    }
  }

  private static String parseLegacyType(String raw) {
    int pipe = raw.indexOf('|');
    if (pipe > 0) {
      return raw.substring(0, pipe);
    }
    return raw;
  }
}
