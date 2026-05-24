package org.receiverman.domains;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ScenarioBuilder {
  private final String name;
  private final List<ConditionBuilder> conditions = new ArrayList<>();
  private ConditionBuilder current;

  ScenarioBuilder(String name) {
    this.name = name;
  }

  public ConditionBuilder expect(String conditionName) {
    current = new ConditionBuilder(this, conditionName);
    conditions.add(current);
    return current;
  }

  public ConditionBuilder thenExpect(String conditionName) {
    return expect(conditionName);
  }

  public SupplierScenario build() {
    return new SupplierScenario(
        name,
        conditions.stream().map(ConditionBuilder::build).toList()
    );
  }

  public ConditionBuilder current() {
    if (current == null) {
      throw new IllegalStateException("Call expect(...) first");
    }
    return current;
  }

  public static final class ConditionBuilder {
    private final ScenarioBuilder parent;
    private final String name;
    private final List<FieldAssertion> assertions = new ArrayList<>();
    private String receiver;
    private Duration maxAge = Duration.ofMinutes(5);
    private Duration timeout = Duration.ofSeconds(10);
    private String emitName = "fulfilled";
    private String emit = "{{raw}}";
    private Map<String, String> payload = Map.of();
    private Map<String, String> effects = Map.of();

    private ConditionBuilder(ScenarioBuilder parent, String name) {
      this.parent = parent;
      this.name = name;
    }

    public ConditionBuilder from(String receiver) {
      this.receiver = receiver;
      return this;
    }

    public FieldBuilder where(String field) {
      return new FieldBuilder(this, field);
    }

    public ConditionBuilder within(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public ConditionBuilder maxAge(Duration maxAge) {
      this.maxAge = maxAge;
      return this;
    }

    public ConditionBuilder emitName(String emitName) {
      this.emitName = emitName;
      return this;
    }

    public ConditionBuilder emit(String emit) {
      this.emit = emit;
      return this;
    }

    /** Shorthand for {@code .emitName(tokenName).emit(tokenTemplate)}. */
    public ConditionBuilder produces(String tokenName, String tokenTemplate) {
      return emitName(tokenName).emit(tokenTemplate);
    }

    public ConditionBuilder payload(Map<String, String> payload) {
      this.payload = Map.copyOf(payload);
      return this;
    }

    public ConditionBuilder effects(Map<String, String> effects) {
      this.effects = Map.copyOf(effects);
      return this;
    }

    public ConditionBuilder thenExpect(String conditionName) {
      return parent.thenExpect(conditionName);
    }

    public SupplierScenario buildScenario() {
      return parent.build();
    }

    private Condition build() {
      return new Condition(
          name,
          receiver,
          assertions,
          maxAge,
          timeout,
          new TokenSpec(emitName, emit, payload, effects)
      );
    }

    private ConditionBuilder add(FieldAssertion assertion) {
      assertions.add(assertion);
      return this;
    }
  }

  public static final class FieldBuilder {
    private final ConditionBuilder condition;
    private final String field;

    private FieldBuilder(ConditionBuilder condition, String field) {
      this.condition = condition;
      this.field = field;
    }

    public ConditionBuilder equalsTo(String value) {
      return condition.add(FieldAssertion.equalsTo(field, value));
    }

    public ConditionBuilder exists() {
      return condition.add(FieldAssertion.exists(field));
    }

    public ConditionBuilder contains(String value) {
      return condition.add(FieldAssertion.contains(field, value));
    }

    public ConditionBuilder matches(String value) {
      return condition.add(FieldAssertion.matches(field, value));
    }
  }
}
