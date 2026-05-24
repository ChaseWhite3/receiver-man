package org.receiverman.descriptors;

import java.time.*;
import java.util.Objects;

/**
 * "World" = time + (optional) minimal context. Here: just a clock and a "now".
 * We can enrich this later with server metadata, correlations, etc.
 */
public final class World {
  private final Clock clock;
  public World(Clock clock) { this.clock = Objects.requireNonNull(clock); }
  Instant now() { return clock.instant(); }
}
