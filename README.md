This is conceptually based on Kripke models and the book "Word Meaning and Montague Grammar" by David R. Dowty.

ReceiverMan is now also runnable as a small CLI application for checking whether supplier event feeds satisfy ordered conditions.

Run the navigable menu with:

```sh
./gradlew run
```

From the menu you can:

- choose a supplier and scenario
- paste received event lines such as `ADT^A01|...` and `ORU^R01|...`
- get a fulfillment report for each required condition
- create a supplier scenario for the current session

Run stream mode with:

```sh
printf 'type=ADT^A01 patientId=P123 supplier=demo\ntype=ORU^R01 patientId=P123 supplier=demo\n' | ./gradlew run --args='stream'
```

Run stream mode with a YAML supplier template:

```sh
printf 'msh.messageType=ADT^A01 msh.messageControlId=ACME-42 pid.patientId=P123\nmsh.messageType=ORU^R01 obr.placerOrderNumber=ORD-9 pid.patientId=P123\n' \
  | ./gradlew run --args='stream --template examples/acme-hl7.yml'
```

Stream mode reads records from standard input. Each record is parsed into fields, forwarded as `FORWARD <raw>`, and matched against the active supplier scenario. When a condition is fulfilled, ReceiverMan emits a token rendered from that condition's template:

```text
FORWARD type=ADT^A01 patientId=P123 supplier=demo
TOKEN P123 admission:P123
FORWARD type=ORU^R01 patientId=P123 supplier=demo
TOKEN P123 result:P123
SCENARIO_FULFILLED P123 Demo Hospital EHR / Admission result flow
```

The default parser supports:

- key/value input: `event=invoice supplier=acme invoice=INV-7`
- simple flat JSON: `{"event":"invoice","supplier":"acme","invoice":"INV-7"}`
- the older message-type-first fallback: `ADT^A01|...`

Conditions are field-based and can use hierarchical paths. For example, a condition can require:

```text
msh.messageType = ADT^A01
msh.messageControlId matches ^ACME-[0-9]+$
pid.patientId exists
```

Then it can render a token such as:

```text
admission:{{pid.patientId}}:{{msh.messageControlId}}
```

Scenario order provides sequencing. Supplier templates should describe ordered conditions; callers do not need to use the lower-level `Intensions.then(...)` API directly.

Template files use this shape:

```yaml
supplier: Acme HL7
routeBy: pid.patientId
scenarios:
  - name: Admission result flow
    expect:
      - name: Admission received
        within: 10s
        maxAge: 5m
        emit: "admission:{{pid.patientId}}:{{msh.messageControlId}}"
        match:
          - field: msh.messageType
            equals: ADT^A01
          - field: msh.messageControlId
            matches: "^ACME-[0-9]+$"
          - field: pid.patientId
            exists: true
```

Supported assertion operators are `equals`, `exists`, `contains`, and `matches`.

## Using ReceiverMan as a Java Library

Build the library JAR:

```sh
./gradlew :app:jar
```

Publish it to your local Maven repository:

```sh
./gradlew :app:publishToMavenLocal
```

Then another Gradle project can import it:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'org.recieverman:receiverman:0.1.0-SNAPSHOT'
}
```

Example usage from another Java project:

```java
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import org.recieverman.descriptors.entities.ParsedEvent;
import org.recieverman.domains.DefaultEventParser;
import org.recieverman.domains.RoutedFulfillment;
import org.recieverman.domains.ScenarioRouter;
import org.recieverman.domains.SupplierScenario;
import org.recieverman.domains.SupplierTemplate;
import org.recieverman.domains.SupplierTemplateLoader;

SupplierTemplate template = new SupplierTemplateLoader().load(Path.of("acme-hl7.yml"));
SupplierScenario scenario = template.supplier().scenarios().getFirst();
ScenarioRouter router = new ScenarioRouter(
    template.supplier(),
    scenario,
    template.routeBy(),
    Clock.systemUTC()
);

ParsedEvent event = new DefaultEventParser().parse(
    "msh.messageType=ADT^A01 msh.messageControlId=ACME-42 pid.patientId=P123",
    Instant.now()
);

router.route(event).map(RoutedFulfillment::fulfillmentToken)
    .ifPresent(token -> System.out.println(token.token()));
```

Stream mode routes records by a correlation field before checking conditions. The default route field is `patientId`, so interleaved records for different patients advance independent scenario instances:

```text
type=ADT^A01 patientId=P1
type=ADT^A01 patientId=P2
type=ORU^R01 patientId=P1
type=ORU^R01 patientId=P2
```

The router is thread-safe for independent route keys. Events for the same route key are serialized through that key's scenario verifier because ordered conditions depend on arrival order.
