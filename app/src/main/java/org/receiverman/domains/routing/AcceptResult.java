package org.receiverman.domains.routing;

import java.util.Optional;
import java.util.function.Consumer;

import org.receiverman.domains.fulfillment.FulfillmentToken;

public record AcceptResult(Status status, FulfillmentToken token, String diagnostic) {
  public enum Status {
    WAITING,
    ADVANCED,
    COMPLETED,
    FAILED,
    IGNORED
  }

  public AcceptResult(Status status, FulfillmentToken token) {
    this(status, token, "");
  }

  public AcceptResult {
    diagnostic = diagnostic == null ? "" : diagnostic;
  }

  public static AcceptResult waiting() {
    return new AcceptResult(Status.WAITING, null);
  }

  public static AcceptResult waiting(String diagnostic) {
    return new AcceptResult(Status.WAITING, null, diagnostic);
  }

  public static AcceptResult advanced(FulfillmentToken token) {
    return new AcceptResult(Status.ADVANCED, token);
  }

  public static AcceptResult completed(FulfillmentToken token) {
    return new AcceptResult(Status.COMPLETED, token);
  }

  public static AcceptResult failed() {
    return new AcceptResult(Status.FAILED, null);
  }

  public static AcceptResult failed(String diagnostic) {
    return new AcceptResult(Status.FAILED, null, diagnostic);
  }

  public static AcceptResult ignored() {
    return new AcceptResult(Status.IGNORED, null);
  }

  public Optional<FulfillmentToken> tokenOptional() {
    return Optional.ofNullable(token);
  }

  public AcceptResult ifFulfilled(Consumer<FulfillmentToken> consumer) {
    tokenOptional().ifPresent(consumer);
    return this;
  }

  public AcceptResult ifComplete(Consumer<FulfillmentToken> consumer) {
    if (status == Status.COMPLETED && token != null) {
      consumer.accept(token);
    }
    return this;
  }

  public AcceptResult ifFailed(Runnable action) {
    if (status == Status.FAILED) {
      action.run();
    }
    return this;
  }
}
