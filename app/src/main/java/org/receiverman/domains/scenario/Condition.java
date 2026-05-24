package org.receiverman.domains.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.fulfillment.FulfillmentToken;
import org.receiverman.domains.supplier.Supplier;

public record Condition(
    String name,
    String receiver,
    List<FieldAssertion> assertions,
    Duration maxAge,
    Duration timeout,
    TokenSpec tokenSpec
) {
  private static final Pattern TEMPLATE_FIELD = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)}}");

  public Condition(String name, String messageType, Duration maxAge, Duration timeout) {
    this(name, null, FieldAssertion.equalsTo("type", messageType), maxAge, timeout, "{{type}}");
  }

  public Condition(
      String name,
      String field,
      String expectedValue,
      Duration maxAge,
      Duration timeout,
      String tokenTemplate
  ) {
    this(name, null, FieldAssertion.equalsTo(field, expectedValue), maxAge, timeout, tokenTemplate);
  }

  public Condition(
      String name,
      FieldAssertion assertion,
      Duration maxAge,
      Duration timeout,
      String tokenTemplate
  ) {
    this(name, null, assertion, maxAge, timeout, tokenTemplate);
  }

  public Condition(
      String name,
      String receiver,
      FieldAssertion assertion,
      Duration maxAge,
      Duration timeout,
      String tokenTemplate
  ) {
    this(name, receiver, List.of(assertion), maxAge, timeout, tokenTemplate);
  }

  public Condition(
      String name,
      List<FieldAssertion> assertions,
      Duration maxAge,
      Duration timeout,
      String tokenTemplate
  ) {
    this(name, null, assertions, maxAge, timeout, TokenSpec.valueOnly(tokenTemplate));
  }

  public Condition(
      String name,
      String receiver,
      List<FieldAssertion> assertions,
      Duration maxAge,
      Duration timeout,
      String tokenTemplate
  ) {
    this(name, receiver, assertions, maxAge, timeout, TokenSpec.valueOnly(tokenTemplate));
  }

  public Condition {
    name = requireText(name, "name");
    receiver = receiver == null || receiver.isBlank() ? null : receiver.trim();
    assertions = List.copyOf(assertions);
    if (assertions.isEmpty()) {
      throw new IllegalArgumentException("assertions must not be empty");
    }
    maxAge = Objects.requireNonNull(maxAge, "maxAge");
    timeout = Objects.requireNonNull(timeout, "timeout");
    tokenSpec = Objects.requireNonNull(tokenSpec, "tokenSpec");

    if (maxAge.isNegative() || maxAge.isZero()) {
      throw new IllegalArgumentException("maxAge must be positive");
    }
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  public String field() {
    return primaryAssertion().field();
  }

  public String expectedValue() {
    return primaryAssertion().value();
  }

  public String messageType() {
    return expectedValue();
  }

  public boolean matches(ParsedEvent event, Instant now) {
    Duration age = Duration.between(event.receivedAt(), now).abs();
    boolean receiverOk = receiver == null || event.field("receiver").map(receiver::equals).orElse(false);
    return receiverOk
        && age.compareTo(maxAge) <= 0
        && assertions.stream().allMatch(assertion -> assertion.matches(event));
  }

  public String diagnose(ParsedEvent event, Instant now) {
    StringBuilder diagnostic = new StringBuilder();
    Duration age = Duration.between(event.receivedAt(), now).abs();
    diagnostic.append("Condition '").append(name).append("' was not fulfilled.");
    diagnostic.append(System.lineSeparator()).append("Expected receiver: ")
        .append(receiver == null ? "<any>" : receiver);
    diagnostic.append(System.lineSeparator()).append("Actual receiver: ")
        .append(event.field("receiver").orElse("<missing>"));
    diagnostic.append(System.lineSeparator()).append("Actual parser: ")
        .append(event.field("parser").orElse("<missing>"));
    diagnostic.append(System.lineSeparator()).append("Expected assertions: ")
        .append(describeAssertions());
    diagnostic.append(System.lineSeparator()).append("Actual fields: ")
        .append(event.fields());
    diagnostic.append(System.lineSeparator()).append("Raw message: ")
        .append(event.raw());

    if (receiver != null && event.field("receiver").map(receiver::equals).orElse(false) == false) {
      diagnostic.append(System.lineSeparator()).append("- receiver expected '")
          .append(receiver).append("' but was '")
          .append(event.field("receiver").orElse("<missing>")).append("'");
    }
    if (age.compareTo(maxAge) > 0) {
      diagnostic.append(System.lineSeparator()).append("- message age ")
          .append(age).append(" exceeded maxAge ").append(maxAge);
    }
    assertions.stream()
        .filter(assertion -> !assertion.matches(event))
        .map(assertion -> "- " + assertion.diagnose(event))
        .forEach(line -> diagnostic.append(System.lineSeparator()).append(line));

    return diagnostic.toString();
  }

  public String describeAssertions() {
    return assertions.stream()
        .map(FieldAssertion::describe)
        .reduce((left, right) -> left + ", " + right)
        .orElse("");
  }

  public String renderToken(ParsedEvent event) {
    return renderTemplate(tokenSpec.valueTemplate(), event);
  }

  public FulfillmentToken renderFulfillment(Supplier supplier, SupplierScenario scenario, ParsedEvent event) {
    return tokenSpec.render(supplier, scenario, this, event);
  }

  public String tokenTemplate() {
    return tokenSpec.valueTemplate();
  }

  String renderTemplate(String template, ParsedEvent event) {
    Matcher matcher = TEMPLATE_FIELD.matcher(template);
    StringBuilder output = new StringBuilder();
    while (matcher.find()) {
      String replacement = event.field(matcher.group(1)).orElse("");
      matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(output);
    return output.toString();
  }

  private FieldAssertion primaryAssertion() {
    return assertions.getFirst();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
