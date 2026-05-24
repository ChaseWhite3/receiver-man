package org.receiverman.descriptors.functional;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * "Verbal space of promises": combinators to build expectations compositionally.
 * Think: meaning-polynomials over an event stream.
 * Polynomial is just to say it doesn't store meanings like a dictionary stores entries.
 *      Instead it generates meanings like a polynomial generates functions (points on a Cartesian grid)
 *      even though its just a mixture of simple addition (you can add negatives) and
 *      multiplication operators over the set of integers.
 *      <p>
 *      i.e. "meaning-polynomial" intensionally simplify in a formal way, or create a nice surface.  
 *        <p>
 *        1.)  x * x + 2x + 1 -> (x + 1)^2 <p>
 *         just as it is easier to think of the right side of the implication above so below:
 *        <p>
 *        2.) BECOME(NOT(ALIVE x)) -> die(x) 
 *         <p> die encodes more and is easier to think about.
 */
public final class Intensions {
  /** Map result (functor map). 
   *  Meaning is tentatively or perfunctorily mapping the space. 
   * <p>
   *    -Think of it as youre holding little hair like feelers (potential extended Predicates) into the extended 
   *        space listening to catch a possible event which we intensionally indicate. 
   *        (Think like cochlear nerve fibers or probes listening for possible sounds)
   * <p>
   *    -when a extended predicate rings true,(evaluates to true, or in failure false) 
   *        that means we found something out about the possible worlds or we found out 
   *        no world fell within the constraint.
   *        so we "then"... (see below "then" function)
   * E is the identifiable entity */
  public static <E, A, B> Intension<E, B> map(Intension<E, A> ia, Function<A, B> f) {
    return (w, s) -> ia.run(w, s).thenApply(f);
  }

  /** FlatMap/then (monadic bind): sequence expectations. 
   * <p> (Things are transpiring, getting more serious) */
  public static <E, A, B> Intension<E, B> then(Intension<E, A> ia, Function<A, Intension<E, B>> k) {
    return (w, s) -> ia.run(w, s).thenCompose(a -> k.apply(a).run(w, s));
  }

  /** Assert-like: keep value, but fail if condition doesn't hold. 
   *    (Its official, report it and the rest of too if it falls within the constraint.) */
  public static <E, A> Intension<E, A> require(Intension<E, A> ia, Predicate<A> ok, String msg) {
    return (w, s) -> ia.run(w, s).thenApply(a -> {
      if (!ok.test(a)) throw new AssertionError(msg + " | got=" + a);
      return a;
    });
  }
}
