package org.receiverman.domains;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ReceiverRegistry {
  private final Map<String, EventParser> parsers = new ConcurrentHashMap<>();

  public ReceiverRegistry() {
    parser("default", new DefaultEventParser());
  }

  public ReceiverRegistry parser(String parserName, EventParser parser) {
    if (parserName == null || parserName.isBlank()) {
      throw new IllegalArgumentException("parserName must not be blank");
    }
    if (parser == null) {
      throw new IllegalArgumentException("parser must not be null");
    }
    parsers.put(parserName.trim(), parser);
    return this;
  }

  public EventParser requireParser(String parserName) {
    return Optional.ofNullable(parsers.get(parserName))
        .orElseThrow(() -> new IllegalArgumentException("No parser registered for " + parserName));
  }
}
