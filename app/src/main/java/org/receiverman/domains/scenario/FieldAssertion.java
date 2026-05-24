package org.receiverman.domains.scenario;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.receiverman.descriptors.entities.ParsedEvent;

public record FieldAssertion(String field, Operator operator, String value) {
  public FieldAssertion {
    field = requireText(field, "field");
    operator = Objects.requireNonNull(operator, "operator");
    value = value == null ? "" : value.trim();
    if (operator.requiresValue() && value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank for " + operator);
    }
    if (operator == Operator.MATCHES) {
      Pattern.compile(value);
    }
  }

  public static FieldAssertion equalsTo(String field, String value) {
    return new FieldAssertion(field, Operator.EQUALS, value);
  }

  public static FieldAssertion exists(String field) {
    return new FieldAssertion(field, Operator.EXISTS, "");
  }

  public static FieldAssertion contains(String field, String value) {
    return new FieldAssertion(field, Operator.CONTAINS, value);
  }

  public static FieldAssertion matches(String field, String value) {
    return new FieldAssertion(field, Operator.MATCHES, value);
  }

  public boolean matches(ParsedEvent event) {
    return switch (operator) {
      case EQUALS -> event.field(field).map(value::equals).orElse(false);
      case EXISTS -> event.field(field).filter(found -> !found.isBlank()).isPresent();
      case CONTAINS -> event.field(field).map(found -> found.contains(value)).orElse(false);
      case MATCHES -> event.field(field).map(found -> Pattern.matches(value, found)).orElse(false);
    };
  }

  public String diagnose(ParsedEvent event) {
    String actual = event.field(field).orElse(null);
    if (actual == null || actual.isBlank()) {
      return field + " expected " + describeExpectation() + " but was missing";
    }
    if (matches(event)) {
      return field + " matched " + describeExpectation() + " with actual " + quote(actual);
    }
    return field + " expected " + describeExpectation() + " but was " + quote(actual);
  }

  public String describe() {
    return switch (operator) {
      case EQUALS -> field + "=" + value;
      case EXISTS -> field + " exists";
      case CONTAINS -> field + " contains " + value;
      case MATCHES -> field + " matches " + value;
    };
  }

  private String describeExpectation() {
    return switch (operator) {
      case EQUALS -> "equal to " + quote(value);
      case EXISTS -> "to exist";
      case CONTAINS -> "to contain " + quote(value);
      case MATCHES -> "to match " + quote(value);
    };
  }

  private static String quote(String value) {
    return "'" + value + "'";
  }

  public enum Operator {
    EQUALS,
    EXISTS,
    CONTAINS,
    MATCHES;

    boolean requiresValue() {
      return this != EXISTS;
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
