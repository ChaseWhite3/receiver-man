package org.receiverman.descriptors.predicates;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.receiverman.descriptors.functional.Intension;

/**
 * Inchoative "BECOME": complete when predicate becomes true on some event.
 * This is edge-triggered: it completes at the first satisfying event.
 */
public final class Become {
  public static <E> Intension<E, E> firstMatch(Predicate<E> pred, Duration timeout) {
    return (world, stream) -> {
      var cf = new CompletableFuture<E>();
      var done = new AtomicBoolean(false);

      // subscribe
      var sub = stream.subscribe(e -> {
        if (done.get()) return;
        if (pred.test(e)) {
          if (done.compareAndSet(false, true)) {
            cf.complete(e);
          }
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
