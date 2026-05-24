package org.receiverman.domains;

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

  public String describe() {
    return switch (operator) {
      case EQUALS -> field + "=" + value;
      case EXISTS -> field + " exists";
      case CONTAINS -> field + " contains " + value;
      case MATCHES -> field + " matches " + value;
    };
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
