package org.receiverman.domains.fulfillment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.receiverman.descriptors.entities.EventBus;
import org.receiverman.descriptors.entities.EventStream;
import org.receiverman.descriptors.entities.ParsedEvent;

/**
 * Collects a sequence of typed values extracted from an event stream, in arrival order.
 * Use {@code ReceiverManBus#track} to obtain one.
 *
 * <p>The series records every value for which the extractor returns a non-empty result.
 * Events where the extractor returns {@link Optional#empty()} are silently skipped, so one
 * receiver can carry many different measurement types and you only see the ones you care about.
 *
 * <pre>{@code
 * ValueSeries<Double> temps = bus.track(
 *     event -> event.field("obx.temperature").map(Double::parseDouble)
 * );
 *
 * // or filter to a specific receiver
 * ValueSeries<Double> temps = bus.track(
 *     "vitals",
 *     event -> event.field("obx.temperature").map(Double::parseDouble)
 * );
 *
 * // after events have arrived:
 * temps.values();          // [36.5, 37.0, 37.8, 38.3]
 * temps.isIncreasing();    // true
 * temps.peak();            // Optional[38.3]
 * temps.trough();          // Optional[36.5]
 * }</pre>
 *
 * <p>Call {@link #stop()} to cancel the subscription when you no longer need it.
 *
 * @param <T> the value type; must implement {@link Comparable} for ordering checks.
 */
public final class ValueSeries<T extends Comparable<T>> {
  private final CopyOnWriteArrayList<T> collected = new CopyOnWriteArrayList<>();
  private final EventStream.Subscription subscription;

  public ValueSeries(EventBus<ParsedEvent> events, Function<ParsedEvent, Optional<T>> extractor) {
    this.subscription = events.subscribe(event ->
        extractor.apply(event).ifPresent(collected::add)
    );
  }

  /**
   * Cancel the subscription. Events arriving after this call will not be added to the series.
   * Safe to call more than once.
   */
  public void stop() {
    subscription.cancel();
  }

  /** All values collected so far, in the order they arrived. */
  public List<T> values() {
    return List.copyOf(collected);
  }

  /** Number of values collected so far. */
  public int size() {
    return collected.size();
  }

  /** First value collected, or empty if nothing has arrived yet. */
  public Optional<T> first() {
    return collected.isEmpty() ? Optional.empty() : Optional.of(collected.get(0));
  }

  /** Most recently collected value, or empty if nothing has arrived yet. */
  public Optional<T> last() {
    return collected.isEmpty() ? Optional.empty() : Optional.of(collected.get(collected.size() - 1));
  }

  /** Largest value by natural ordering, or empty if nothing has arrived yet. */
  public Optional<T> peak() {
    return collected.stream().max(Comparator.naturalOrder());
  }

  /** Smallest value by natural ordering, or empty if nothing has arrived yet. */
  public Optional<T> trough() {
    return collected.stream().min(Comparator.naturalOrder());
  }

  /**
   * True if every value is strictly greater than the one before it.
   * A series of 0 or 1 values trivially satisfies this.
   */
  public boolean isIncreasing() {
    return isOrdered((prev, next) -> prev.compareTo(next) < 0);
  }

  /**
   * True if every value is greater than or equal to the one before it (allows plateaus).
   */
  public boolean isNonDecreasing() {
    return isOrdered((prev, next) -> prev.compareTo(next) <= 0);
  }

  /**
   * True if every value is strictly less than the one before it.
   */
  public boolean isDecreasing() {
    return isOrdered((prev, next) -> prev.compareTo(next) > 0);
  }

  /**
   * True if every value is less than or equal to the one before it (allows plateaus).
   */
  public boolean isNonIncreasing() {
    return isOrdered((prev, next) -> prev.compareTo(next) >= 0);
  }

  private boolean isOrdered(BiPredicate<T, T> prevBeforeNext) {
    List<T> snapshot = values();
    for (int i = 1; i < snapshot.size(); i++) {
      if (!prevBeforeNext.test(snapshot.get(i - 1), snapshot.get(i))) return false;
    }
    return true;
  }

  @Override
  public String toString() {
    return "ValueSeries" + collected;
  }
}
