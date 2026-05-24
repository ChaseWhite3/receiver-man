package org.receiverman.domains.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

import org.receiverman.descriptors.entities.Event;
import org.receiverman.descriptors.functional.Intension;
import org.receiverman.descriptors.functional.Intensions;
import org.receiverman.descriptors.predicates.Become;

/**
 * Domain-specific: *some* Type expectations.
 * "The server events" -> we model as: events will arrive; define which inchoative states matter.
 */
public final class EventExpect {
  /**
   * Expect *some* Type message of a given type, but constrained by "delay":
   * i.e., only accept events received within maxAge of "now".
   *
   * This avoids stashing: we ignore old events rather than collecting them.
   */
  public static Intension<Event, Event> expectTypeFresh(
      String msgType,
      Duration maxAge,
      Duration timeout
  ) {
    return Become.firstMatch(ev -> {
      boolean typeOk = msgType.equals(ev.msgType());
      boolean freshOk = Duration.between(ev.receivedAt(), Instant.now()).abs().compareTo(maxAge) <= 0;
      return typeOk && freshOk;
    }, timeout);
  }

  /**
   * Often you want "after I see A, I then see B".
   * This is an *inchoative chain* without stashing.
   */
  public static Intension<Event, Event> afterSeeingThen(
      Intension<Event, Event> first,
      Function<Event, Intension<Event, Event>> next
  ) {
    return Intensions.then(first, next);
  }
}
