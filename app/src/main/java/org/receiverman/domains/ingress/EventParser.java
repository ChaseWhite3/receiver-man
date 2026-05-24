package org.receiverman.domains.ingress;

import java.time.Instant;

import org.receiverman.descriptors.entities.ParsedEvent;

@FunctionalInterface
public interface EventParser {
  ParsedEvent parse(String raw, Instant receivedAt);
}
