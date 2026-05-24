package org.receiverman.domains;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.receiverman.descriptors.entities.ParsedEvent;

public final class ReceiverManRuntime {
  private final SupplierTemplate template;
  private final ReceiverRegistry registry;
  private final ScenarioRouter router;
  private final Clock clock;

  public ReceiverManRuntime(
      SupplierTemplate template,
      SupplierScenario scenario,
      ReceiverRegistry registry,
      Clock clock
  ) {
    this.template = template;
    this.registry = registry;
    this.clock = clock;
    this.router = new ScenarioRouter(template.supplier(), scenario, template.routeBy(), clock);
  }

  public static ReceiverManRuntime fromTemplate(
      SupplierTemplate template,
      ReceiverRegistry registry,
      Clock clock
  ) {
    return new ReceiverManRuntime(
        template,
        template.supplier().scenarios().getFirst(),
        registry,
        clock
    );
  }

  public Optional<RoutedFulfillment> accept(String receiverId, String raw) {
    ReceiverSpec spec = template.receiver(receiverId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown receiver: " + receiverId));
    ParsedEvent parsed = registry.requireParser(spec.parser()).parse(raw, Instant.now(clock));
    ParsedEvent withReceiver = withReceiver(parsed, spec.id());
    return router.route(withReceiver);
  }

  public ScenarioRouter router() {
    return router;
  }

  private static ParsedEvent withReceiver(ParsedEvent event, String receiverId) {
    Map<String, String> fields = new LinkedHashMap<>(event.fields());
    fields.put("receiver", receiverId);
    return ParsedEvent.of(event.raw(), event.receivedAt(), fields);
  }
}
