package org.receiverman.domains;

import java.util.LinkedHashMap;
import java.util.Map;

import org.receiverman.descriptors.entities.ParsedEvent;

public record TokenSpec(
    String nameTemplate,
    String valueTemplate,
    Map<String, String> payloadTemplates,
    Map<String, String> effectTemplates
) {
  public TokenSpec {
    nameTemplate = normalizeTemplate(nameTemplate, "fulfilled");
    valueTemplate = normalizeTemplate(valueTemplate, "{{raw}}");
    payloadTemplates = Map.copyOf(payloadTemplates == null ? Map.of() : payloadTemplates);
    effectTemplates = Map.copyOf(effectTemplates == null ? Map.of() : effectTemplates);
  }

  public TokenSpec(String nameTemplate, String valueTemplate, Map<String, String> payloadTemplates) {
    this(nameTemplate, valueTemplate, payloadTemplates, Map.of());
  }

  public static TokenSpec valueOnly(String valueTemplate) {
    return new TokenSpec("fulfilled", valueTemplate, Map.of(), Map.of());
  }

  public FulfillmentToken render(
      Supplier supplier,
      SupplierScenario scenario,
      Condition condition,
      ParsedEvent event
  ) {
    Map<String, String> payload = new LinkedHashMap<>();
    payloadTemplates.forEach((key, template) -> payload.put(key, condition.renderTemplate(template, event)));
    Map<String, String> effects = new LinkedHashMap<>();
    effectTemplates.forEach((key, template) -> effects.put(key, condition.renderTemplate(template, event)));
    return new FulfillmentToken(
        supplier,
        scenario,
        condition,
        event,
        condition.renderTemplate(nameTemplate, event),
        condition.renderTemplate(valueTemplate, event),
        payload,
        effects
    );
  }

  private static String normalizeTemplate(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
