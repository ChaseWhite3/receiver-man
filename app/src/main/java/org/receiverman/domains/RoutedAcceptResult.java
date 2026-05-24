package org.receiverman.domains;

import java.util.Optional;
import java.util.function.Consumer;

public record RoutedAcceptResult(String routeKey, AcceptResult result) {
  public Optional<RoutedFulfillment> fulfillment() {
    return result.tokenOptional().map(token -> new RoutedFulfillment(routeKey, token));
  }

  public RoutedAcceptResult ifFulfilled(Consumer<RoutedFulfillment> consumer) {
    fulfillment().ifPresent(consumer);
    return this;
  }
}
