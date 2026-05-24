package org.recieverman.descriptors.functional;

import org.recieverman.descriptors.World;
import java.util.concurrent.CompletionStage;
import org.recieverman.descriptors.entities.EventStream;

/**
 * Intension: a program that *subscribes to a stream of events*
 * and returns a Promise/Future of some result.
 */
@FunctionalInterface
public interface Intension<E, R> {
  CompletionStage<R> run(World world, EventStream<E> stream);
}