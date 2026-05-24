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
TOKEN P123 fulfilled admission:P123
FORWARD type=ORU^R01 patientId=P123 supplier=demo
TOKEN P123 fulfilled result:P123
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

Internally, a template scenario is compiled once into a reusable `CompiledScenario`. Each route key then starts its own mutable `ScenarioRun` from that compiled plan:

```text
YAML template
  -> SupplierScenario
  -> ScenarioCompiler
  -> CompiledScenario
  -> ScenarioRouter creates one ScenarioRun per route key
```

The same `ScenarioCompiler` can also compile a scenario into the lower-level monadic stream model:

```text
SupplierScenario
  -> ScenarioCompiler
  -> Intension<ParsedEvent, FulfillmentToken>
```

That form subscribes to an `EventStream<ParsedEvent>`, sequences the ordered conditions with `Intensions.then(...)`, and completes with the final fulfillment token.

Template files use this shape:

```yaml
supplier: Acme HL7
routeBy: pid.patientId
receivers:
  - id: hl7
    parser: hl7
  - id: streaming
    parser: streaming
scenarios:
  - name: Admission result flow
    expect:
      - name: Admission received
        within: 10s
        maxAge: 5m
        emitName: admission-received
        emit: "admission:{{pid.patientId}}:{{msh.messageControlId}}"
        payload:
          patientId: "{{pid.patientId}}"
          messageControlId: "{{msh.messageControlId}}"
        effects:
          nextReceiver: streaming
        match:
          - field: msh.messageType
            equals: ADT^A01
          - field: msh.messageControlId
            matches: "^ACME-[0-9]+$"
          - field: pid.patientId
            exists: true
```

Supported assertion operators are `equals`, `exists`, `contains`, and `matches`. Fulfillment tokens expose structured fields:

```java
token.name();     // e.g. "admission-received"
token.value();    // e.g. "admission:P123:ACME-42"
token.payload();  // e.g. {patientId=P123, messageControlId=ACME-42}
token.effects();  // e.g. {nextReceiver=streaming}
```

`token.token()` is kept as a compatibility alias for `token.value()`.

Stages can also declare which receiver dependency they expect:

```yaml
- name: Admission received
  from: hl7
  effects:
    nextReceiver: streaming
  match:
    - field: msh.messageType
      equals: ADT^A01

- name: Streaming update received
  from: streaming
  match:
    - field: eventType
      equals: patient.updated
```

ReceiverMan treats `from` as a match against the event field `receiver`. It does not switch real dependencies itself; the host app owns that:

```java
router.route(event).map(RoutedFulfillment::fulfillmentToken)
    .ifPresent(token -> {
        String nextReceiver = token.effects().get("nextReceiver");
        if (nextReceiver != null) {
            receiverManager.switchTo(nextReceiver);
        }
    });
```

Templates can declare receiver IDs and the parser name each one expects:

```yaml
receivers:
  - id: hl7
    parser: hl7
  - id: streaming
    parser: streaming
```

The host app registers parser implementations and calls the runtime with the receiver ID plus raw message:

```java
ReceiverRegistry registry = new ReceiverRegistry()
    .parser("hl7", new Hl7Parser())
    .parser("streaming", new StreamingParser());

SupplierTemplate template = new SupplierTemplateLoader().load(Path.of("acme-hl7.yml"));
ReceiverManRuntime runtime = ReceiverManRuntime.fromTemplate(
    template,
    registry,
    Clock.systemUTC()
);

runtime.accept("hl7", rawHl7Message)
    .map(RoutedFulfillment::fulfillmentToken)
    .ifPresent(token -> {
        String nextReceiver = token.effects().get("nextReceiver");
        if (nextReceiver != null) {
            receiverManager.switchTo(nextReceiver);
        }
    });
```

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
    implementation 'org.receiverman:receiverman:0.1.0-SNAPSHOT'
}
```

Example usage from another Java project:

```java
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.DefaultEventParser;
import org.receiverman.domains.RoutedFulfillment;
import org.receiverman.domains.ScenarioRouter;
import org.receiverman.domains.SupplierScenario;
import org.receiverman.domains.SupplierTemplate;
import org.receiverman.domains.SupplierTemplateLoader;

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
    .ifPresent(token -> {
        System.out.println(token.name());
        System.out.println(token.value());
        System.out.println(token.payload());
    });
```

Stream mode routes records by a correlation field before checking conditions. The default route field is `patientId`, so interleaved records for different patients advance independent scenario instances:

```text
type=ADT^A01 patientId=P1
type=ADT^A01 patientId=P2
type=ORU^R01 patientId=P1
type=ORU^R01 patientId=P2
```

The router is thread-safe for independent route keys. Events for the same route key are serialized through that key's scenario verifier because ordered conditions depend on arrival order.
