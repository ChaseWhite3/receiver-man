package org.receiverman.descriptors.predicates;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.receiverman.descriptors.functional.Intension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inchoative "BECOME": complete when predicate becomes true on some event.
 * This is edge-triggered: it completes at the first satisfying event.
 */
public final class Become {
  private static final Logger LOG = LoggerFactory.getLogger(Become.class);

  public static <E> Intension<E, E> firstMatch(Predicate<E> pred, Duration timeout) {
    return (world, stream) -> {
      var cf = new CompletableFuture<E>();
      var done = new AtomicBoolean(false);
      var missCount = new AtomicInteger(0);
      var lastMiss = new AtomicReference<E>();

      // subscribe
      var sub = stream.subscribe(e -> {
        if (done.get()) return;
        if (pred.test(e)) {
          if (done.compareAndSet(false, true)) {
            cf.complete(e);
          }
        } else {
          int count = missCount.incrementAndGet();
          lastMiss.set(e);
          LOG.debug("BECOME near-miss #{} — event did not satisfy predicate: {}", count, e);
        }
      });

      // timeout
      ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "become-timeout");
        t.setDaemon(true);
        return t;
      });
      ses.schedule(() -> {
        if (done.compareAndSet(false, true)) {
          E last = lastMiss.get();
          int count = missCount.get();
          if (last != null) {
            LOG.warn(
                ">>>>> BECOME TIMED OUT after {} — {} near-miss event(s) received but none satisfied the predicate."
                    + " Last near-miss event: {}",
                timeout, count, last
            );
          } else {
            LOG.warn(
                ">>>>> BECOME TIMED OUT after {} — NO events were received at all. "
                    + "Check that the event source is running and routing to the correct stream.",
                timeout
            );
          }
          cf.completeExceptionally(new TimeoutException("BECOME timeout after " + timeout));
        }
      }, timeout.toMillis(), TimeUnit.MILLISECONDS);

      // cleanup
      cf.whenComplete((r, ex) -> {
        sub.cancel();
        ses.shutdownNow();
      });

      return cf;
    };
  }
}
