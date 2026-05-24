package org.receiverman.domains;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.receiverman.descriptors.entities.Event;
import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.FulfillmentReport.ConditionResult;

public final class ScenarioVerifier {
  private final EventParser parser;

  public ScenarioVerifier() {
    this(new DefaultEventParser());
  }

  public ScenarioVerifier(EventParser parser) {
    this.parser = parser;
  }

  public FulfillmentReport verify(
      Supplier supplier,
      SupplierScenario scenario,
      List<Event> events,
      Clock clock
  ) {
    List<ParsedEvent> parsed = events.stream()
        .map(event -> parser.parse(event.raw(), event.receivedAt()))
        .toList();
    return verifyParsed(supplier, scenario, parsed, clock);
  }

  public FulfillmentReport verifyParsed(
      Supplier supplier,
      SupplierScenario scenario,
      List<ParsedEvent> events,
      Clock clock
  ) {
    List<ParsedEvent> ordered = events.stream()
        .sorted(Comparator.comparing(ParsedEvent::receivedAt))
        .toList();
    List<ConditionResult> results = new ArrayList<>();
    Instant cursor = ordered.isEmpty() ? clock.instant() : ordered.getFirst().receivedAt();
    int nextEventIndex = 0;
    boolean blocked = false;

    for (Condition condition : scenario.conditions()) {
      if (blocked) {
        results.add(new ConditionResult(
            condition,
            false,
            "Skipped because an earlier condition was not fulfilled."
        ));
        continue;
      }

      Match match = findMatch(condition, ordered, nextEventIndex, cursor, clock.instant());
      if (match == null) {
        blocked = true;
        results.add(new ConditionResult(
            condition,
            false,
            "No event matching " + condition.describeAssertions() + " arrived within "
                + formatDuration(condition.timeout()) + "."
        ));
        continue;
      }

      nextEventIndex = match.index() + 1;
      cursor = match.event().receivedAt();
      results.add(new ConditionResult(
          condition,
          true,
          "Matched " + match.event().raw() + " at " + match.event().receivedAt()
              + " and emitted " + condition.renderToken(match.event()) + "."
      ));
    }

    return new FulfillmentReport(supplier, scenario, results);
  }

  private Match findMatch(
      Condition condition,
      List<ParsedEvent> events,
      int startIndex,
      Instant cursor,
      Instant now
  ) {
    Instant deadline = cursor.plus(condition.timeout());
    for (int i = startIndex; i < events.size(); i++) {
      ParsedEvent event = events.get(i);
      if (event.receivedAt().isBefore(cursor)) {
        continue;
      }
      if (event.receivedAt().isAfter(deadline)) {
        return null;
      }
      if (condition.matches(event, now)) {
        return new Match(i, event);
      }
    }
    return null;
  }

  private static String formatDuration(Duration duration) {
    if (duration.toSecondsPart() == 0 && duration.toMinutes() > 0) {
      return duration.toMinutes() + "m";
    }
    if (duration.toMinutes() == 0) {
      return duration.toSeconds() + "s";
    }
    return duration.toMinutes() + "m " + duration.toSecondsPart() + "s";
  }

  private record Match(int index, ParsedEvent event) {}
}
