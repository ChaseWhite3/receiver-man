package org.receiverman.domains;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.receiverman.domains.fulfillment.FulfillmentToken;
import org.receiverman.domains.ingress.EventParser;
import org.receiverman.domains.ingress.ReceiverRegistry;
import org.receiverman.domains.ingress.ReceiverSpec;
import org.receiverman.domains.scenario.ScenarioBuilder;
import org.receiverman.domains.scenario.SupplierScenario;
import org.receiverman.domains.supplier.Supplier;
import org.receiverman.domains.supplier.SupplierTemplate;

/**
 * Fluent builder for {@link ReceiverManBus}.
 *
 * <p>Entry point is {@link ReceiverManBus#builder()}. Build up receivers and scenarios in one
 * chain, then call {@link StepContext#build()} to create the live bus.
 *
 * <pre>{@code
 * ReceiverManBus bus = ReceiverManBus.builder()
 *     .receiver("hapi-hl7", new HapiHl7Parser())
 *     .scenario("HAPI ADT flow")
 *         .expect("Admission received")
 *             .from("hapi-hl7")
 *             .where("msh.messageType").equalsTo("ADT^A01")
 *             .where("msh.messageControlId").equalsTo("MSG-1")
 *             .where("pid.patientId").equalsTo("P123")
 *             .produces("admission-received", "admission:{{pid.patientId}}:{{msh.messageControlId}}")
 *     .build();
 *
 * // await a single result
 * FulfillmentToken token = bus.await().toCompletableFuture().get(10, SECONDS);
 *
 * // or collect the full ordered step trace
 * List<FulfillmentToken> trace = bus.awaitAll().toCompletableFuture().get(10, SECONDS);
 * }</pre>
 *
 * <h3>Builder flow</h3>
 * <ol>
 *   <li>{@link #receiver(String, EventParser)} — register one receiver per raw message source.</li>
 *   <li>{@link #scenario(String)} — open a named scenario, returns {@link ScenarioContext}.</li>
 *   <li>{@link ScenarioContext#expect(String)} — name the first step, returns {@link StepContext}.</li>
 *   <li>{@link StepContext#where(String)} — open a field assertion, returns {@link FieldContext}.</li>
 *   <li>{@link FieldContext#equalsTo(String)} (and siblings) — close the assertion, back to {@link StepContext}.</li>
 *   <li>{@link StepContext#produces(String, String)} — name the step's output token (optional).</li>
 *   <li>{@link StepContext#thenExpect(String)} — add the next ordered step (multi-step scenarios).</li>
 *   <li>{@link StepContext#build()} — seal the scenario and create the {@link ReceiverManBus}.</li>
 * </ol>
 */
public final class ReceiverManBuilder {
  private String supplierName = "supplier";
  private final LinkedHashMap<String, EventParser> parsers = new LinkedHashMap<>();
  private final List<SupplierScenario> scenarios = new ArrayList<>();
  private Clock clock = Clock.systemUTC();

  ReceiverManBuilder() {}

  /**
   * Override the supplier name used in diagnostics and fulfillment tokens.
   * Defaults to {@code "supplier"} when not set.
   */
  public ReceiverManBuilder supplierName(String name) {
    this.supplierName = name;
    return this;
  }

  /**
   * Override the clock used for event timestamps and step timeouts.
   * Defaults to {@link Clock#systemUTC()}. Useful in tests to control time.
   */
  public ReceiverManBuilder clock(Clock clock) {
    this.clock = clock;
    return this;
  }

  /**
   * Register a receiver by id and the {@link EventParser} that will parse raw messages
   * arriving on that receiver.
   *
   * <p>The id here is the same string passed to {@link ReceiverManBus#accept(String, String)}
   * and to {@link StepContext#from(String)} in the scenario chain. Call this once per
   * distinct message source.
   *
   * <pre>{@code
   * .receiver("hapi-hl7", new HapiHl7Parser())
   * .receiver("fhir-r4",  new FhirR4Parser())
   * }</pre>
   */
  public ReceiverManBuilder receiver(String id, EventParser parser) {
    parsers.put(id, parser);
    return this;
  }

  /**
   * Add a pre-built {@link SupplierScenario} (e.g. built via
   * {@link ReceiverManIntensions#scenario(String)}) instead of using the inline builder chain.
   * Can be called multiple times to register multiple scenarios on the same bus.
   */
  public ReceiverManBuilder scenario(SupplierScenario scenario) {
    scenarios.add(scenario);
    return this;
  }

  /**
   * Open an inline scenario with the given name and return a {@link ScenarioContext} to
   * define its steps. Call {@link StepContext#build()} (or {@link StepContext#done()} for
   * multi-scenario setups) to close the scenario and build the bus.
   *
   * @param name Human-readable scenario name. Appears in logs, diagnostics, and
   *             fulfillment tokens. Also used as the argument to
   *             {@link ReceiverManBus#await(String)}.
   */
  public ScenarioContext scenario(String name) {
    return new ScenarioContext(this, new ScenarioBuilder(name));
  }

  ReceiverManBuilder addScenario(SupplierScenario scenario) {
    scenarios.add(scenario);
    return this;
  }

  /**
   * Finalise configuration and create the {@link ReceiverManBus}.
   * All {@link #receiver} and {@link #scenario} calls must come before this.
   * Prefer calling {@link StepContext#build()} at the end of the inline chain instead of
   * calling this directly.
   */
  public ReceiverManBus build() {
    ReceiverRegistry registry = new ReceiverRegistry();
    parsers.forEach(registry::parser);
    List<ReceiverSpec> specs = parsers.keySet().stream()
        .map(id -> new ReceiverSpec(id, id))
        .toList();
    Supplier supplier = new Supplier(supplierName, scenarios);
    SupplierTemplate template = new SupplierTemplate(supplier, "pid.patientId", specs);
    return ReceiverManBus.fromTemplate(template, registry, clock);
  }

  // -------------------------------------------------------------------------
  // Fluent scenario-building contexts
  // -------------------------------------------------------------------------

  /**
   * Intermediate context returned by {@link ReceiverManBuilder#scenario(String)}.
   * Call {@link #expect(String)} to define the first (or only) step.
   */
  public static final class ScenarioContext {
    private final ReceiverManBuilder builder;
    final ScenarioBuilder sb;

    ScenarioContext(ReceiverManBuilder builder, ScenarioBuilder sb) {
      this.builder = builder;
      this.sb = sb;
    }

    /**
     * Define the first step of this scenario and give it a human-readable name.
     * Returns a {@link StepContext} where you add field assertions, timing constraints,
     * and an optional output token via {@link StepContext#produces(String, String)}.
     *
     * @param stepName Label for this step. Appears in near-miss warnings and diagnostics.
     */
    public StepContext expect(String stepName) {
      return new StepContext(this, sb.expect(stepName));
    }
  }

  /**
   * Intermediate context for configuring one step of a scenario.
   * Returned by {@link ScenarioContext#expect(String)} and {@link #thenExpect(String)}.
   *
   * <p>A step is fulfilled when an incoming event satisfies all of its {@link #where} assertions,
   * arrives from the expected {@link #from} receiver (if set), within the {@link #within} timeout.
   */
  public static final class StepContext {
    private final ScenarioContext scenario;
    private final ScenarioBuilder.ConditionBuilder cb;

    StepContext(ScenarioContext scenario, ScenarioBuilder.ConditionBuilder cb) {
      this.scenario = scenario;
      this.cb = cb;
    }

    /**
     * Restrict this step to events arriving from a specific receiver id.
     * Must match the id passed to {@link ReceiverManBus#accept(String, String)} and registered
     * via {@link ReceiverManBuilder#receiver(String, EventParser)}.
     * If omitted, events from any receiver are considered.
     */
    public StepContext from(String receiver) {
      cb.from(receiver);
      return this;
    }

    /**
     * Maximum time to wait for this step to be fulfilled, measured from when the previous
     * step completed (or from bus creation for the first step).
     * Defaults to {@code 10 seconds}. When exceeded the scenario fails and an error is logged.
     */
    public StepContext within(Duration timeout) {
      cb.within(timeout);
      return this;
    }

    /**
     * Maximum age of an incoming event for it to be considered by this step.
     * Events older than this threshold are silently ignored.
     * Defaults to {@code 5 minutes}.
     */
    public StepContext maxAge(Duration maxAge) {
      cb.maxAge(maxAge);
      return this;
    }

    /**
     * Open a field assertion on {@code field} and return a {@link FieldContext} to specify
     * the operator ({@link FieldContext#equalsTo}, {@link FieldContext#contains}, etc.).
     * Multiple {@code where} calls on the same step are ANDed together — all must pass.
     *
     * <p>Field names correspond to keys produced by the {@link EventParser} registered for
     * this receiver, e.g. {@code "msh.messageType"}, {@code "pid.patientId"}.
     *
     * <pre>{@code
     * .where("msh.messageType").equalsTo("ADT^A01")
     * .where("pid.patientId").exists()
     * }</pre>
     */
    public FieldContext where(String field) {
      return new FieldContext(this, cb.where(field));
    }

    /**
     * Declare the named output this step emits when fulfilled.
     * {@code tokenName} becomes {@link FulfillmentToken#name()} and
     * {@code tokenTemplate} becomes {@link FulfillmentToken#value()} after field interpolation.
     *
     * <p>Template syntax: {@code {{fieldName}}} is replaced with the matched event's field value.
     *
     * <pre>{@code
     * .produces("admission-received", "admission:{{pid.patientId}}:{{msh.messageControlId}}")
     * // → token.name()  = "admission-received"
     * // → token.value() = "admission:P123:MSG-1"
     * }</pre>
     *
     * <p>If omitted, {@code token.name()} defaults to {@code "fulfilled"} and
     * {@code token.value()} defaults to the raw message.
     */
    public StepContext produces(String tokenName, String tokenTemplate) {
      cb.emitName(tokenName).emit(tokenTemplate);
      return this;
    }

    /**
     * Add the next ordered step to this scenario. Steps are evaluated in the order they are
     * declared — this step must be fulfilled before the next one is attempted.
     *
     * <pre>{@code
     * .expect("Admission received")
     *     .from("hapi-hl7")
     *     .where("msh.messageType").equalsTo("ADT^A01")
     *     .produces("admitted", "{{pid.patientId}}")
     * .thenExpect("Order placed")
     *     .from("hapi-hl7")
     *     .where("msh.messageType").equalsTo("ORM^O01")
     *     .produces("order-placed", "{{obr.placerOrderNumber}}")
     * .build()
     * }</pre>
     */
    public StepContext thenExpect(String stepName) {
      return new StepContext(scenario, scenario.sb.thenExpect(stepName));
    }

    /**
     * Close this scenario and return to the {@link ReceiverManBuilder} so you can register
     * additional scenarios or change top-level config before calling {@link ReceiverManBuilder#build()}.
     *
     * <pre>{@code
     * ReceiverManBus bus = ReceiverManBus.builder()
     *     .receiver("hapi-hl7", parser)
     *     .scenario("ADT flow").expect("Admit").where("msh.messageType").equalsTo("ADT^A01").done()
     *     .scenario("ORM flow").expect("Order").where("msh.messageType").equalsTo("ORM^O01").done()
     *     .build();
     * }</pre>
     */
    public ReceiverManBuilder done() {
      scenario.builder.addScenario(scenario.sb.build());
      return scenario.builder;
    }

    /**
     * Seal this scenario and build the {@link ReceiverManBus} in one call.
     * Equivalent to {@code .done().build()}.
     * Use {@link #done()} instead when registering multiple scenarios.
     */
    public ReceiverManBus build() {
      return done().build();
    }
  }

  /**
   * Intermediate context returned by {@link StepContext#where(String)}.
   * Choose an operator to complete the assertion and return to {@link StepContext}.
   */
  public static final class FieldContext {
    private final StepContext step;
    private final ScenarioBuilder.FieldBuilder fb;

    FieldContext(StepContext step, ScenarioBuilder.FieldBuilder fb) {
      this.step = step;
      this.fb = fb;
    }

    /**
     * Assert the field is present and its value exactly equals {@code value}.
     * Returns to {@link StepContext} so you can chain more {@code .where()} calls or move on.
     */
    public StepContext equalsTo(String value) {
      fb.equalsTo(value);
      return step;
    }

    /**
     * Assert the field is present and non-blank, regardless of its value.
     * Returns to {@link StepContext}.
     */
    public StepContext exists() {
      fb.exists();
      return step;
    }

    /**
     * Assert the field value contains {@code value} as a substring.
     * Returns to {@link StepContext}.
     */
    public StepContext contains(String value) {
      fb.contains(value);
      return step;
    }

    /**
     * Assert the field value matches the given regular expression (full match via
     * {@link java.util.regex.Pattern#matches}).
     * Returns to {@link StepContext}.
     */
    public StepContext matches(String value) {
      fb.matches(value);
      return step;
    }
  }
}
