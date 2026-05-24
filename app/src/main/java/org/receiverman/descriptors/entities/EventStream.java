package org.receiverman.descriptors.entities;

import java.util.function.Consumer;

/** A very small event stream abstraction (push-based). */
public interface EventStream<E> {
  Subscription subscribe(Consumer<E> onEvent);
  interface Subscription { void cancel(); }
}
