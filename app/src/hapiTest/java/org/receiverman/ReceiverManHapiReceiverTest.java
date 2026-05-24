package org.receiverman;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.ReceiverManBus;
import org.receiverman.domains.fulfillment.FulfillmentToken;
import org.receiverman.domains.fulfillment.ValueSeries;
import org.receiverman.domains.ingress.DefaultEventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import ca.uhn.hl7v2.util.Terser;

public class ReceiverManHapiReceiverTest {
  private static final Logger LOG = LoggerFactory.getLogger(ReceiverManHapiReceiverTest.class);

  @Test
  public void hapiSimpleServerCanFeedReceiverManBus() throws Exception {
    ReceiverManBus receiverMan = ReceiverManBus.builder()
        .receiver("hapi-hl7", new HapiHl7Parser())
        .scenario("HAPI ADT flow")
            .expect("Admission received")
                .from("hapi-hl7")
                .where("msh.messageType").equalsTo("ADT^A01")
                .where("msh.messageControlId").equalsTo("MSG-1")
                .where("pid.patientId").equalsTo("P123")
                .produces("hapi-admission-received", "admission:{{pid.patientId}}:{{msh.messageControlId}}")
        .build();
    var pendingSteps = receiverMan.awaitAll(); // subscribe before the message arrives

    int port = freePort();
    HapiContext hapi = new DefaultHapiContext();
    HL7Service server = hapi.newServer(port, false);
    server.registerApplication("ADT", "A01", new ReceiverManHapiApplication(receiverMan));
    server.startAndWait();

    try {
      var conn = hapi.newClient("127.0.0.1", port, false);
      try {
        conn.getInitiator().sendAndReceive(hapi.getPipeParser().parse(adtA01()));
      } finally {
        conn.close();
      }

      List<FulfillmentToken> steps = receiverMan.await(pendingSteps, Duration.ofSeconds(3));
      LOG.info("Scenario trace: {}", steps.stream().map(t -> t.name() + "=" + t.value()).toList());

      FulfillmentToken token = steps.getLast();

      assertField(token.event(), "msh.messageType",      "ADT^A01");
      assertField(token.event(), "msh.messageControlId", "MSG-1");
      assertField(token.event(), "pid.patientId",        "P123");
      assertField(token.event(), "receiver",             "hapi-hl7");
      assertField(token.event(), "parser",               "hapi-hl7");

      assertField("token.name",  token.name(),  "hapi-admission-received");
      assertField("token.value", token.value(), "admission:P123:MSG-1");
    } finally {
      server.stopAndWait();
      hapi.close();
    }
  }

  @Test
  public void valueSeriesTracksMonotonicTemperatureIncrease() {
    ReceiverManBus bus = ReceiverManBus.builder()
        .receiver("vitals", new DefaultEventParser())
        .scenario("Temperature escalation")
            .expect("Fever onset")
                .from("vitals")
                .where("msh.messageType").equalsTo("ORU^R01")
                .where("obx.temperature").matches("3[89]\\.[0-9]+")
                .produces("fever-detected", "temp={{obx.temperature}}")
        .build();

    ValueSeries<Double> temps = bus.track(
        "vitals",
        event -> event.field("obx.temperature").map(Double::parseDouble)
    );
    CompletionStage<List<FulfillmentToken>> pending = bus.awaitAll();

    bus.accept("vitals", "msh.messageType=ORU^R01 obx.temperature=36.5");
    bus.accept("vitals", "msh.messageType=ORU^R01 obx.temperature=37.0");
    bus.accept("vitals", "msh.messageType=ORU^R01 obx.temperature=37.8");
    bus.accept("vitals", "msh.messageType=ORU^R01 obx.temperature=38.3");

    bus.await(pending, Duration.ofSeconds(3));

    LOG.info("Temperature series: {}", temps.values());

    assertEquals(temps.values(), List.of(36.5, 37.0, 37.8, 38.3));
    assertTrue(temps.isIncreasing(), "temperatures should be strictly increasing");
    assertEquals(temps.peak().orElseThrow(), 38.3);
    assertEquals(temps.trough().orElseThrow(), 36.5);
    assertEquals(temps.first().orElseThrow(), 36.5);
    assertEquals(temps.last().orElseThrow(), 38.3);
    assertEquals(temps.size(), 4);
  }

  private static void assertField(ParsedEvent event, String field, String expected) {
    assertField(field, event.field(field).orElse("<missing>"), expected);
  }

  private static void assertField(String label, String actual, String expected) {
    if (expected.equals(actual)) {
      LOG.info("OK    {} = '{}'", label, actual);
    } else {
      LOG.warn(">>>>> MISMATCH {} — expected='{}' actual='{}'", label, expected, actual);
    }
    assertEquals(actual, expected);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static String adtA01() {
    return "MSH|^~\\&|SENDER|FAC|RECEIVER|FAC|20260524120000||ADT^A01|MSG-1|P|2.5\r"
        + "PID|1||P123^^^MRN||DOE^JANE\r";
  }

  private static final class ReceiverManHapiApplication implements ReceivingApplication<Message> {
    private final ReceiverManBus receiverMan;

    private ReceiverManHapiApplication(ReceiverManBus receiverMan) {
      this.receiverMan = receiverMan;
    }

    @Override
    public boolean canProcess(Message message) {
      return true;
    }

    @Override
    public Message processMessage(Message message, Map<String, Object> metadata)
        throws ReceivingApplicationException, HL7Exception {
      receiverMan.accept("hapi-hl7", message.encode());
      try {
        return message.generateACK();
      } catch (IOException e) {
        throw new RuntimeException("Failed to generate ACK", e);
      }
    }
  }

  private static final class HapiHl7Parser implements org.receiverman.domains.ingress.EventParser {
    private static final Logger LOG = LoggerFactory.getLogger(HapiHl7Parser.class);
    private final HapiContext context = new DefaultHapiContext();

    @Override
    public ParsedEvent parse(String raw, Instant receivedAt) {
      try {
        Message message = context.getPipeParser().parse(raw);
        // Consider MSH|^~\&|SENDER|FAC|RECEIVER|FAC|20260524120000||ADT^A01|MSG-1|P|2.5
        //
        // HL7 fields are numbered.
        //
        // MSH-1  = |
        // MSH-2  = ^~\&
        // MSH-3  = SENDER
        // ...
        // MSH-9  = ADT^A01
        //
        // But MSH-9 itself is composite:
        //ADT^A01
        // ↑   ↑
        // |   |
        // 1   2
        // So terser.get("/MSH-9-1") means:
        // segment MSH
        // field 9
        // component 1
        Terser terser = new Terser(message);
        String type = terser.get("/MSH-9-1") + "^" + terser.get("/MSH-9-2");

        // here goes ReceiverMan's internal model. I.e. We extract to normalize the fields.
        ParsedEvent event = ParsedEvent.of(raw, receivedAt, Map.of(
            "msh.messageType",      type,
            "msh.messageControlId", terser.get("/MSH-10"),
            "pid.patientId",        terser.get("/PID-3-1")
        ));
        LOG.debug("HapiHl7Parser parsed: fields={}", event.fields());
        return event;
      } catch (HL7Exception e) {
        LOG.error(">>>>> PARSER FAILED — could not parse HL7 message.{}Raw: {}", System.lineSeparator(), raw, e);
        throw new IllegalArgumentException("Unable to parse HL7 message", e);
      }
    }
  }
}
