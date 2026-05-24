package org.receiverman.domains.supplier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.receiverman.domains.scenario.Condition;
import org.receiverman.domains.scenario.FieldAssertion;
import org.receiverman.domains.scenario.SupplierScenario;

public final class SupplierCatalog {
  private final List<Supplier> suppliers = new ArrayList<>();

  public SupplierCatalog() {
    suppliers.add(new Supplier("Demo Hospital EHR", List.of(
        new SupplierScenario("Admission result flow", List.of(
            new Condition("Admission received", List.of(
                FieldAssertion.equalsTo("msh.messageType", "ADT^A01"),
                FieldAssertion.matches("msh.messageControlId", "^ACME-[0-9]+$")
            ), Duration.ofMinutes(5), Duration.ofSeconds(10), "admission:{{pid.patientId}}:{{msh.messageControlId}}"),
            new Condition("Observation result received", List.of(
                FieldAssertion.equalsTo("msh.messageType", "ORU^R01"),
                FieldAssertion.exists("obr.placerOrderNumber")
            ), Duration.ofMinutes(5), Duration.ofSeconds(10), "result:{{pid.patientId}}:{{obr.placerOrderNumber}}")
        )),
        new SupplierScenario("Order then result flow", List.of(
            new Condition("Order received", "msh.messageType", "ORM^O01", Duration.ofMinutes(5), Duration.ofSeconds(10), "order:{{obr.placerOrderNumber}}"),
            new Condition("Result received", "msh.messageType", "ORU^R01", Duration.ofMinutes(5), Duration.ofSeconds(10), "result:{{obr.placerOrderNumber}}")
        ))
    )));
    suppliers.add(new Supplier("Reference Lab", List.of(
        new SupplierScenario("Results only", List.of(
            new Condition("Observation result received", "msh.messageType", "ORU^R01", Duration.ofMinutes(5), Duration.ofSeconds(10), "lab-result:{{accession}}")
        ))
    )));
  }

  public List<Supplier> suppliers() {
    return List.copyOf(suppliers);
  }

  public Supplier addSupplier(String name, List<SupplierScenario> scenarios) {
    Supplier supplier = new Supplier(name, scenarios);
    suppliers.add(supplier);
    return supplier;
  }
}
