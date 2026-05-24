package org.receiverman;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.receiverman.descriptors.entities.Event;
import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.Condition;
import org.receiverman.domains.DefaultEventParser;
import org.receiverman.domains.EventParser;
import org.receiverman.domains.FulfillmentToken;
import org.receiverman.domains.FulfillmentReport;
import org.receiverman.domains.RoutedFulfillment;
import org.receiverman.domains.ScenarioRouter;
import org.receiverman.domains.ScenarioVerifier;
import org.receiverman.domains.Supplier;
import org.receiverman.domains.SupplierCatalog;
import org.receiverman.domains.SupplierScenario;
import org.receiverman.domains.SupplierTemplate;
import org.receiverman.domains.SupplierTemplateLoader;

public class ReceiverMan {
  private final SupplierCatalog catalog;
  private final ScenarioVerifier verifier;
  private final EventParser parser;
  private final Clock clock;

  public ReceiverMan() {
    this(new SupplierCatalog(), new DefaultEventParser(), Clock.systemUTC());
  }

  ReceiverMan(SupplierCatalog catalog, EventParser parser, Clock clock) {
    this.catalog = catalog;
    this.parser = parser;
    this.verifier = new ScenarioVerifier(parser);
    this.clock = clock;
  }

  public String getGreeting() {
    return "ReceiverMan validates supplier event conditions.";
  }

  public static void main(String[] args) {
    ReceiverMan app = new ReceiverMan();
    if (args.length > 0 && "stream".equalsIgnoreCase(args[0])) {
      try {
        app.runStreamCommand(args, System.in, System.out);
      } catch (RuntimeException | java.io.IOException ex) {
        System.err.println("ERROR " + ex.getMessage());
      }
    } else {
      app.run(System.in, System.out);
    }
  }

  private void runStreamCommand(
      String[] args,
      java.io.InputStream input,
      PrintStream out
  ) throws java.io.IOException {
    StreamOptions options = StreamOptions.parse(args);
    if (options.templatePath == null) {
      runDefaultStream(input, out, options.routeBy);
      return;
    }

    SupplierTemplate template = new SupplierTemplateLoader().load(resolveTemplatePath(options.templatePath));
    SupplierScenario scenario = chooseScenario(template.supplier(), options.scenario);
    String routeField = options.routeBy == null ? template.routeBy() : options.routeBy;
    stream(input, out, template.supplier(), scenario, routeField, false);
  }

  void run(java.io.InputStream input, PrintStream out) {
    Scanner scanner = new Scanner(input);
    out.println(getGreeting());

    while (true) {
      out.println();
      out.println("1. Run supplier scenario");
      out.println("2. Run supplier stream");
      out.println("3. List suppliers");
      out.println("4. Create supplier scenario");
      out.println("5. Exit");
      out.print("Choose: ");

      if (!scanner.hasNextLine()) {
        out.println();
        out.println("Done.");
        return;
      }
      String choice = scanner.nextLine().trim();
      switch (choice) {
        case "1" -> runSupplierScenario(scanner, out);
        case "2" -> runSupplierStream(scanner, out);
        case "3" -> printSuppliers(out);
        case "4" -> createSupplierScenario(scanner, out);
        case "5", "q", "quit", "exit" -> {
          out.println("Done.");
          return;
        }
        default -> out.println("Choose 1, 2, 3, 4, or 5.");
      }
    }
  }

  private void runDefaultStream(java.io.InputStream input, PrintStream out, String routeBy) {
    Supplier supplier = catalog.suppliers().getFirst();
    SupplierScenario scenario = supplier.scenarios().getFirst();
    stream(input, out, supplier, scenario, routeBy == null ? "patientId" : routeBy, false);
  }

  private void runSupplierScenario(Scanner scanner, PrintStream out) {
    Supplier supplier = chooseSupplier(scanner, out);
    if (supplier == null) return;

    SupplierScenario scenario = chooseScenario(scanner, out, supplier);
    if (scenario == null) return;

    out.println();
    out.println("Paste supplier events in arrival order.");
    out.println("Use HL7 message type first, for example ADT^A01|... or ORU^R01|...");
    out.println("Blank line runs the check.");

    List<Event> events = new ArrayList<>();
    while (true) {
      out.print("event> ");
      if (!scanner.hasNextLine()) break;
      String line = scanner.nextLine();
      if (line.isBlank()) break;
      events.add(new Event(line.trim(), Instant.now(clock)));
    }

    FulfillmentReport report = verifier.verify(supplier, scenario, events, clock);
    printReport(out, report);
  }

  private void runSupplierStream(Scanner scanner, PrintStream out) {
    Supplier supplier = chooseSupplier(scanner, out);
    if (supplier == null) return;

    SupplierScenario scenario = chooseScenario(scanner, out, supplier);
    if (scenario == null) return;

    out.println();
    out.println("Streaming. Each line is forwarded as FORWARD <raw>.");
    out.println("Tokens are emitted as TOKEN <value> when a condition is fulfilled.");
    out.print("Route/correlation field [patientId]: ");
    String routeField = scanner.nextLine().trim();
    if (routeField.isBlank()) {
      routeField = "patientId";
    }
    out.println("Blank line stops the stream.");
    stream(scanner, out, supplier, scenario, routeField, true);
  }

  private void stream(
      java.io.InputStream input,
      PrintStream out,
      Supplier supplier,
      SupplierScenario scenario,
      String routeField,
      boolean stopOnBlank
  ) {
    stream(new Scanner(input), out, supplier, scenario, routeField, stopOnBlank);
  }

  private void stream(
      Scanner scanner,
      PrintStream out,
      Supplier supplier,
      SupplierScenario scenario,
      String routeField,
      boolean stopOnBlank
  ) {
    ScenarioRouter router = new ScenarioRouter(supplier, scenario, routeField, clock);
    while (scanner.hasNextLine()) {
      String line = scanner.nextLine();
      if (stopOnBlank && line.isBlank()) {
        return;
      }
      if (line.isBlank()) {
        continue;
      }

      ParsedEvent event = parser.parse(line, Instant.now(clock));
      out.println("FORWARD " + event.raw());
      router.route(event).ifPresent(fulfillment -> printRoutedFulfillment(out, router, fulfillment));
    }
  }

  private void printRoutedFulfillment(
      PrintStream out,
      ScenarioRouter router,
      RoutedFulfillment fulfillment
  ) {
    FulfillmentToken token = fulfillment.fulfillmentToken();
    out.println("TOKEN " + fulfillment.routeKey() + " " + token.name() + " " + token.value());
    router.run(fulfillment.routeKey())
        .filter(run -> run.complete())
        .ifPresent(ignored -> out.println("SCENARIO_FULFILLED "
            + fulfillment.routeKey()
            + " "
            + token.supplier().name()
            + " / "
            + token.scenario().name()));
  }

  private void createSupplierScenario(Scanner scanner, PrintStream out) {
    out.print("Supplier name: ");
    String supplierName = scanner.nextLine().trim();
    out.print("Scenario name: ");
    String scenarioName = scanner.nextLine().trim();

    List<Condition> conditions = new ArrayList<>();
    while (true) {
      out.print("Field to match [type], blank when done: ");
      String field = scanner.nextLine().trim();
      if (field.isBlank()) break;
      out.print("Expected value: ");
      String expectedValue = scanner.nextLine().trim();

      Duration timeout = readDuration(scanner, out, "Timeout seconds after previous condition", 10);
      Duration maxAge = readDuration(scanner, out, "Maximum accepted event age seconds", 300);
      out.print("Token template [{{" + field + "}}]: ");
      String tokenTemplate = scanner.nextLine().trim();
      conditions.add(new Condition(
          "See " + field + "=" + expectedValue,
          field,
          expectedValue,
          maxAge,
          timeout,
          tokenTemplate
      ));
    }

    if (conditions.isEmpty()) {
      out.println("No conditions entered; nothing was created.");
      return;
    }

    Supplier supplier = catalog.addSupplier(
        supplierName,
        List.of(new SupplierScenario(scenarioName, conditions))
    );
    out.println("Created supplier " + supplier.name() + " with " + conditions.size() + " condition(s).");
  }

  private Duration readDuration(Scanner scanner, PrintStream out, String label, long defaultSeconds) {
    while (true) {
      out.print(label + " [" + defaultSeconds + "]: ");
      String value = scanner.nextLine().trim();
      if (value.isBlank()) {
        return Duration.ofSeconds(defaultSeconds);
      }
      try {
        long seconds = Long.parseLong(value);
        if (seconds > 0) {
          return Duration.ofSeconds(seconds);
        }
      } catch (NumberFormatException ignored) {
      }
      out.println("Enter a positive whole number of seconds.");
    }
  }

  private Supplier chooseSupplier(Scanner scanner, PrintStream out) {
    List<Supplier> suppliers = catalog.suppliers();
    out.println();
    for (int i = 0; i < suppliers.size(); i++) {
      out.println((i + 1) + ". " + suppliers.get(i).name());
    }
    out.print("Supplier: ");
    return pick(scanner.nextLine(), suppliers, out);
  }

  private SupplierScenario chooseScenario(Scanner scanner, PrintStream out, Supplier supplier) {
    List<SupplierScenario> scenarios = supplier.scenarios();
    out.println();
    for (int i = 0; i < scenarios.size(); i++) {
      SupplierScenario scenario = scenarios.get(i);
      out.println((i + 1) + ". " + scenario.name());
      for (Condition condition : scenario.conditions()) {
        out.println("   - " + condition.name() + ": " + condition.describeAssertions());
      }
    }
    out.print("Scenario: ");
    return pick(scanner.nextLine(), scenarios, out);
  }

  private SupplierScenario chooseScenario(Supplier supplier, String selector) {
    if (selector == null || selector.isBlank()) {
      return supplier.scenarios().getFirst();
    }
    try {
      int index = Integer.parseInt(selector.trim()) - 1;
      if (index >= 0 && index < supplier.scenarios().size()) {
        return supplier.scenarios().get(index);
      }
    } catch (NumberFormatException ignored) {
    }
    return supplier.scenarios().stream()
        .filter(scenario -> scenario.name().equals(selector))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + selector));
  }

  private Path resolveTemplatePath(String templatePath) {
    Path path = Path.of(templatePath);
    if (path.isAbsolute() || java.nio.file.Files.exists(path)) {
      return path;
    }
    Path fromRepoRoot = Path.of("..").resolve(path).normalize();
    if (java.nio.file.Files.exists(fromRepoRoot)) {
      return fromRepoRoot;
    }
    return path;
  }

  private <T> T pick(String value, List<T> options, PrintStream out) {
    try {
      int index = Integer.parseInt(value.trim()) - 1;
      if (index >= 0 && index < options.size()) {
        return options.get(index);
      }
    } catch (NumberFormatException ignored) {
    }
    out.println("Selection cancelled.");
    return null;
  }

  private void printSuppliers(PrintStream out) {
    for (Supplier supplier : catalog.suppliers()) {
      out.println();
      out.println(supplier.name());
      for (SupplierScenario scenario : supplier.scenarios()) {
        out.println("  " + scenario.name());
        for (Condition condition : scenario.conditions()) {
          out.println("    " + condition.describeAssertions()
              + " within " + condition.timeout().toSeconds() + "s");
        }
      }
    }
  }

  private void printReport(PrintStream out, FulfillmentReport report) {
    out.println();
    out.println(report.supplier().name() + " / " + report.scenario().name());
    out.println(report.fulfilled() ? "FULFILLED" : "NOT FULFILLED");
    for (FulfillmentReport.ConditionResult result : report.results()) {
      out.println((result.fulfilled() ? "[x] " : "[ ] ")
          + result.condition().name()
          + " - " + result.detail());
    }
  }

  private record StreamOptions(String templatePath, String routeBy, String scenario) {
    private static StreamOptions parse(String[] args) {
      String templatePath = null;
      String routeBy = null;
      String scenario = null;

      for (int i = 1; i < args.length; i++) {
        String arg = args[i];
        switch (arg) {
          case "--template" -> templatePath = requireValue(args, ++i, arg);
          case "--routeBy" -> routeBy = requireValue(args, ++i, arg);
          case "--scenario" -> scenario = requireValue(args, ++i, arg);
          default -> throw new IllegalArgumentException("Unknown stream option: " + arg);
        }
      }
      return new StreamOptions(templatePath, routeBy, scenario);
    }

    private static String requireValue(String[] args, int index, String option) {
      if (index >= args.length || args[index].startsWith("--")) {
        throw new IllegalArgumentException(option + " requires a value");
      }
      return args[index];
    }
  }
}
