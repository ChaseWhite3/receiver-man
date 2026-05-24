package org.recieverman.domains;

public record SupplierTemplate(Supplier supplier, String routeBy) {
  public SupplierTemplate {
    if (routeBy == null || routeBy.isBlank()) {
      routeBy = "patientId";
    } else {
      routeBy = routeBy.trim();
    }
  }
}
