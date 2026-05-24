package org.recieverman.domains;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.recieverman.descriptors.entities.ParsedEvent;

public record Condition(
    String name,
    List<FieldAssertion> assertions,
    Duration maxAge,
    Duration timeout,
    String tokenTemplate
) {
  private static final Pattern TEMPLATE_FIELD = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)}}");

  public Condition(String name, String messageType, Duration maxAge, Duration timeout) {
    this(name, FieldAssertion.equalsTo("type", messageType), maxAge, timeout, "{{type}}");
  }

  public Condition(
      String name,
      String field,
      String expectedValue,
      Duration maxAge,
      Duration timeout,
      String tokenTemplate
  ) {
    this(name, FieldAssertion.equalsTo(field, expectedValue), maxAge, timeout, tokenTemplate);
  }

  public Condition(
      String name,
      FieldAssertion assertion,
      Duration maxAge,
      Duration timeout,
      String tokenTemplate
  ) {
    this(name, List.of(assertion), maxAge, timeout, tokenTemplate);
  }

  public Condition {
    name = requireText(name, "name");
    assertions = List.copyOf(assertions);
    if (assertions.isEmpty()) {
      throw new IllegalArgumentException("assertions must not be empty");
    }
    maxAge = Objects.requireNonNull(maxAge, "maxAge");
    timeout = Objects.requireNonNull(timeout, "timeout");
    tokenTemplate = tokenTemplate == null || tokenTemplate.isBlank() ? "{{raw}}" : tokenTemplate.trim();

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
    return age.compareTo(maxAge) <= 0 && assertions.stream().allMatch(assertion -> assertion.matches(event));
  }

  public String describeAssertions() {
    return assertions.stream()
        .map(FieldAssertion::describe)
        .reduce((left, right) -> left + ", " + right)
        .orElse("");
  }

  public String renderToken(ParsedEvent event) {
    Matcher matcher = TEMPLATE_FIELD.matcher(tokenTemplate);
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
