package org.receiverman.domains;

import java.time.Clock;
import java.util.Optional;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReceiverManRuntime {
  private static final Logger LOG = LoggerFactory.getLogger(ReceiverManRuntime.class);

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
    return acceptResult(receiverId, raw).fulfillment();
  }

  public RoutedAcceptResult acceptResult(String receiverId, String raw) {
    ParsedEvent event = ReceiverInput.parse(template, registry, clock, receiverId, raw);
    LOG.debug(
        "ReceiverMan routing message from receiver='{}', parser='{}', routeField='{}', fields={}, raw='{}'",
        event.field("receiver").orElse("<missing>"),
        event.field("parser").orElse("<missing>"),
        router.routeField(),
        event.fields(),
        event.raw()
    );
    RoutedAcceptResult result = router.routeResult(event);
    if (result.result().status() == AcceptResult.Status.WAITING
        || result.result().status() == AcceptResult.Status.FAILED
        || result.result().status() == AcceptResult.Status.IGNORED) {
      LOG.warn(
          "ReceiverMan did not fulfill routeKey='{}', status='{}'.{}{}",
          result.routeKey(),
          result.result().status(),
          System.lineSeparator(),
          result.result().diagnostic()
      );
    }
    return result;
  }

  public ScenarioRouter router() {
    return router;
  }
}
