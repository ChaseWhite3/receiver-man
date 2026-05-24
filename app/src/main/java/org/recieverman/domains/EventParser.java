package org.recieverman.domains;

import java.time.Instant;

import org.recieverman.descriptors.entities.ParsedEvent;

@FunctionalInterface
public interface EventParser {
  ParsedEvent parse(String raw, Instant receivedAt);
}
