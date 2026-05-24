package org.receiverman.domains.supplier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.receiverman.domains.ingress.ReceiverSpec;
import org.receiverman.domains.scenario.Condition;
import org.receiverman.domains.scenario.FieldAssertion;
import org.receiverman.domains.scenario.SupplierScenario;
import org.receiverman.domains.scenario.TokenSpec;

public final class SupplierTemplateLoader {
  public SupplierTemplate load(Path path) throws IOException {
    return parse(Files.readAllLines(path));
  }

  SupplierTemplate parse(List<String> lines) {
    String supplierName = null;
    String routeBy = "patientId";
    List<ReceiverSpec> receivers = new ArrayList<>();
    ReceiverBuilder receiver = null;
    List<SupplierScenario> scenarios = new ArrayList<>();
    ScenarioBuilder scenario = null;
    ConditionBuilder condition = null;
    AssertionBuilder assertion = null;

    for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
      String rawLine = lines.get(lineNumber);
      String withoutComment = stripComment(rawLine);
      if (withoutComment.isBlank()) {
        continue;
      }

      int indent = indentOf(withoutComment);
      String text = withoutComment.trim();

      if (indent == 0) {
        if (receiver != null) {
          receivers.add(receiver.build());
          receiver = null;
        }
        if (text.startsWith("supplier:")) {
          supplierName = valueAfterColon(text);
        } else if (text.startsWith("routeBy:")) {
          routeBy = valueAfterColon(text);
        } else if ("receivers:".equals(text) || "scenarios:".equals(text)) {
          continue;
        } else {
          throw parseError(lineNumber, "Unknown top-level key: " + text);
        }
        continue;
      }

      if (indent == 2 && text.startsWith("- id:")) {
        if (receiver != null) {
          receivers.add(receiver.build());
        }
        receiver = new ReceiverBuilder(valueAfterColon(text.substring(2).trim()));
        continue;
      }

      if (indent == 4 && receiver != null && text.startsWith("parser:")) {
        receiver.parser = valueAfterColon(text);
        continue;
      }

      if (indent == 2 && text.startsWith("- name:")) {
        if (receiver != null) {
          receivers.add(receiver.build());
          receiver = null;
        }
        if (condition != null) {
          scenario.conditions.add(condition.build());
          condition = null;
        }
        if (assertion != null) {
          throw parseError(lineNumber, "Assertion was not attached to a condition");
        }
        if (scenario != null) {
          scenarios.add(scenario.build());
        }
        scenario = new ScenarioBuilder(valueAfterColon(text.substring(2).trim()));
        continue;
      }

      if (scenario == null) {
        throw parseError(lineNumber, "Scenario field appeared before a scenario name");
      }

      if (indent == 4) {
        if (!"expect:".equals(text)) {
          throw parseError(lineNumber, "Unknown scenario key: " + text);
        }
        continue;
      }

      if (indent == 6 && text.startsWith("- name:")) {
        if (assertion != null) {
          condition.assertions.add(assertion.build());
          assertion = null;
        }
        if (condition != null) {
          scenario.conditions.add(condition.build());
        }
        condition = new ConditionBuilder(valueAfterColon(text.substring(2).trim()));
        continue;
      }

      if (condition == null) {
        throw parseError(lineNumber, "Condition field appeared before a condition name");
      }

      if (indent == 8) {
        if (assertion != null) {
          condition.assertions.add(assertion.build());
          assertion = null;
        }
        if (text.startsWith("within:")) {
          condition.timeout = parseDuration(valueAfterColon(text), lineNumber);
        } else if (text.startsWith("maxAge:")) {
          condition.maxAge = parseDuration(valueAfterColon(text), lineNumber);
        } else if (text.startsWith("from:")) {
          condition.receiver = valueAfterColon(text);
        } else if (text.startsWith("emit:")) {
          condition.emit = valueAfterColon(text);
        } else if (text.startsWith("emitName:")) {
          condition.emitName = valueAfterColon(text);
        } else if ("payload:".equals(text)) {
          condition.inPayload = true;
          condition.inEffects = false;
        } else if ("effects:".equals(text)) {
          condition.inEffects = true;
          condition.inPayload = false;
        } else if (!"match:".equals(text)) {
          throw parseError(lineNumber, "Unknown condition key: " + text);
        }
        continue;
      }

      if (indent == 10 && condition.inPayload && !text.startsWith("- field:")) {
        int separator = text.indexOf(':');
        if (separator < 1) {
          throw parseError(lineNumber, "Invalid payload field: " + text);
        }
        condition.payload.put(
            text.substring(0, separator).trim(),
            unquote(text.substring(separator + 1).trim())
        );
        continue;
      }

      if (indent == 10 && condition.inEffects && !text.startsWith("- field:")) {
        int separator = text.indexOf(':');
        if (separator < 1) {
          throw parseError(lineNumber, "Invalid effect field: " + text);
        }
        condition.effects.put(
            text.substring(0, separator).trim(),
            unquote(text.substring(separator + 1).trim())
        );
        continue;
      }

      if (indent == 10 && text.startsWith("- field:")) {
        if (assertion != null) {
          condition.assertions.add(assertion.build());
        }
        condition.inPayload = false;
        condition.inEffects = false;
        assertion = new AssertionBuilder(valueAfterColon(text.substring(2).trim()));
        continue;
      }

      if (indent == 12) {
        if (assertion == null) {
          throw parseError(lineNumber, "Assertion operator appeared before field");
        }
        if (text.startsWith("equals:")) {
          assertion.operator = FieldAssertion.Operator.EQUALS;
          assertion.value = valueAfterColon(text);
        } else if (text.startsWith("contains:")) {
          assertion.operator = FieldAssertion.Operator.CONTAINS;
          assertion.value = valueAfterColon(text);
        } else if (text.startsWith("matches:")) {
          assertion.operator = FieldAssertion.Operator.MATCHES;
          assertion.value = valueAfterColon(text);
        } else if (text.startsWith("exists:")) {
          assertion.operator = FieldAssertion.Operator.EXISTS;
          assertion.value = valueAfterColon(text);
        } else {
          throw parseError(lineNumber, "Unknown assertion operator: " + text);
        }
        continue;
      }

      throw parseError(lineNumber, "Unsupported indentation or field: " + text);
    }

    if (assertion != null) {
      condition.assertions.add(assertion.build());
    }
    if (receiver != null) {
      receivers.add(receiver.build());
    }
    if (condition != null) {
      scenario.conditions.add(condition.build());
    }
    if (scenario != null) {
      scenarios.add(scenario.build());
    }
    if (supplierName == null || supplierName.isBlank()) {
      throw new IllegalArgumentException("Template must define supplier");
    }
    if (scenarios.isEmpty()) {
      throw new IllegalArgumentException("Template must define at least one scenario");
    }
    return new SupplierTemplate(new Supplier(supplierName, scenarios), routeBy, receivers);
  }

  private static Duration parseDuration(String value, int lineNumber) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    try {
      if (normalized.endsWith("ms")) {
        return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2)));
      }
      if (normalized.endsWith("s")) {
        return Duration.ofSeconds(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
      }
      if (normalized.endsWith("m")) {
        return Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
      }
      if (normalized.endsWith("h")) {
        return Duration.ofHours(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
      }
      return Duration.ofSeconds(Long.parseLong(normalized));
    } catch (NumberFormatException ex) {
      throw parseError(lineNumber, "Invalid duration: " + value);
    }
  }

  private static String stripComment(String line) {
    boolean quoted = false;
    char quote = '\0';
    for (int i = 0; i < line.length(); i++) {
      char current = line.charAt(i);
      if ((current == '"' || current == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
        if (!quoted) {
          quoted = true;
          quote = current;
        } else if (quote == current) {
          quoted = false;
        }
      }
      if (current == '#' && !quoted) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static int indentOf(String line) {
    int indent = 0;
    while (indent < line.length() && line.charAt(indent) == ' ') {
      indent++;
    }
    return indent;
  }

  private static String valueAfterColon(String text) {
    int separator = text.indexOf(':');
    if (separator < 0) {
      throw new IllegalArgumentException("Expected ':' in " + text);
    }
    return unquote(text.substring(separator + 1).trim());
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static IllegalArgumentException parseError(int lineNumber, String message) {
    return new IllegalArgumentException("Template line " + (lineNumber + 1) + ": " + message);
  }

  private static final class ScenarioBuilder {
    private final String name;
    private final List<Condition> conditions = new ArrayList<>();

    private ScenarioBuilder(String name) {
      this.name = name;
    }

    private SupplierScenario build() {
      return new SupplierScenario(name, conditions);
    }
  }

  private static final class ReceiverBuilder {
    private final String id;
    private String parser = "default";

    private ReceiverBuilder(String id) {
      this.id = id;
    }

    private ReceiverSpec build() {
      return new ReceiverSpec(id, parser);
    }
  }

  private static final class ConditionBuilder {
    private final String name;
    private final List<FieldAssertion> assertions = new ArrayList<>();
    private String receiver;
    private Duration maxAge = Duration.ofMinutes(5);
    private Duration timeout = Duration.ofSeconds(10);
    private String emitName = "fulfilled";
    private String emit = "{{raw}}";
    private final Map<String, String> payload = new LinkedHashMap<>();
    private final Map<String, String> effects = new LinkedHashMap<>();
    private boolean inPayload;
    private boolean inEffects;

    private ConditionBuilder(String name) {
      this.name = name;
    }

    private Condition build() {
      return new Condition(name, receiver, assertions, maxAge, timeout, new TokenSpec(emitName, emit, payload, effects));
    }
  }

  private static final class AssertionBuilder {
    private final String field;
    private FieldAssertion.Operator operator;
    private String value = "";

    private AssertionBuilder(String field) {
      this.field = field;
    }

    private FieldAssertion build() {
      if (operator == null) {
        throw new IllegalArgumentException("Assertion for " + field + " must define an operator");
      }
      return new FieldAssertion(field, operator, value);
    }
  }
}
