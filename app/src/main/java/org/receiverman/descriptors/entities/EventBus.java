package org.receiverman.descriptors.entities;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Simple in-memory event bus; receiver side is push-based. */
public final class EventBus<E> implements EventStream<E> {
  private final CopyOnWriteArrayList<Consumer<E>> subs = new CopyOnWriteArrayList<>();

  public void emit(E e) {
    for (var s : subs) s.accept(e);
  }

  @Override
  public Subscription subscribe(Consumer<E> onEvent) {
    subs.add(onEvent);
    return () -> subs.remove(onEvent);
  }
}
