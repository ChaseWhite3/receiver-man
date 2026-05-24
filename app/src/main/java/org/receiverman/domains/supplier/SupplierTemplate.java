package org.receiverman.domains.supplier;

import java.util.List;
import java.util.Optional;

import org.receiverman.domains.ingress.ReceiverSpec;

public record SupplierTemplate(Supplier supplier, String routeBy, List<ReceiverSpec> receivers) {
  public SupplierTemplate(Supplier supplier, String routeBy) {
    this(supplier, routeBy, List.of(new ReceiverSpec("default", "default")));
  }

  public SupplierTemplate {
    if (routeBy == null || routeBy.isBlank()) {
      routeBy = "patientId";
    } else {
      routeBy = routeBy.trim();
    }
    receivers = List.copyOf(receivers == null ? List.of() : receivers);
    if (receivers.isEmpty()) {
      receivers = List.of(new ReceiverSpec("default", "default"));
    }
  }

  public Optional<ReceiverSpec> receiver(String receiverId) {
    return receivers.stream()
        .filter(receiver -> receiver.id().equals(receiverId))
        .findFirst();
  }
}
