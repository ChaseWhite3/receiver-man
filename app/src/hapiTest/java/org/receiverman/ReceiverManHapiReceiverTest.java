package org.receiverman;

import static org.testng.Assert.assertEquals;
import java.io.IOException;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.FulfillmentToken;
import org.receiverman.domains.ReceiverManBus;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.Parser;
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
    CompletionStage<List<FulfillmentToken>> trace = receiverMan.awaitAll();

    int port = freePort();
    HapiContext hapi = new DefaultHapiContext();
    HL7Service server = hapi.newServer(port, false);
    ReceiverManHapiApplication application = new ReceiverManHapiApplication(receiverMan);
    server.registerApplication("ADT", "A01", application);
    server.startAndWait();

    try {
      Parser parser = hapi.getPipeParser();
      Message message = parser.parse(adtA01());
      Connection connection = hapi.newClient("127.0.0.1", port, false);
      try {
        connection.getInitiator().sendAndReceive(message);
      } finally {
        connection.close();
      }

      ParsedEvent accepted;
      try {
        accepted = application.accepted.toCompletableFuture().get(3, TimeUnit.SECONDS);
        LOG.info("Parser produced fields: {}", accepted.fields());
      } catch (Exception e) {
        LOG.error(">>>>> PARSER STAGE FAILED — accepted event was never completed. "
            + "The HapiHl7Parser likely threw or the message was never received.", e);
        throw e;
      }

      logAssert("msh.messageType", accepted.field("msh.messageType").orElse("<missing>"), "ADT^A011");
      assertEquals(accepted.field("msh.messageType").orElseThrow(), "ADT^A011");

      logAssert("msh.messageControlId", accepted.field("msh.messageControlId").orElse("<missing>"), "MSG-1");
      assertEquals(accepted.field("msh.messageControlId").orElseThrow(), "MSG-1");

      logAssert("pid.patientId", accepted.field("pid.patientId").orElse("<missing>"), "P123");
      assertEquals(accepted.field("pid.patientId").orElseThrow(), "P123");

      logAssert("receiver", accepted.field("receiver").orElse("<missing>"), "hapi-hl7");
      assertEquals(accepted.field("receiver").orElseThrow(), "hapi-hl7");

      logAssert("parser", accepted.field("parser").orElse("<missing>"), "hapi-hl7");
      assertEquals(accepted.field("parser").orElseThrow(), "hapi-hl7");

      List<FulfillmentToken> steps;
      try {
        steps = trace.toCompletableFuture().get(3, TimeUnit.SECONDS);
        LOG.info("Scenario trace ({} step(s)):", steps.size());
        for (int i = 0; i < steps.size(); i++) {
          FulfillmentToken t = steps.get(i);
          LOG.info("  step {}: name='{}', value='{}'", i + 1, t.name(), t.value());
        }
      } catch (Exception e) {
        LOG.error(">>>>> BECAUSE/BECOME STAGE FAILED — scenario fulfillment was never completed. "
            + "The scenario predicate never matched within its timeout window.", e);
        throw e;
      }

      FulfillmentToken token = steps.getLast();

      logAssert("token.name",  token.name(),  "hapi-admission-received");
      assertEquals(token.name(), "hapi-admission-received");

      logAssert("token.value", token.value(), "admission:P123:MSG-1");
      assertEquals(token.value(), "admission:P123:MSG-1");

      logAssert("token.event.receiver", token.event().field("receiver").orElse("<missing>"), "hapi-hl7");
      assertEquals(token.event().field("receiver").orElseThrow(), "hapi-hl7");
    } finally {
      server.stopAndWait();
      hapi.close();
    }
  }

  private static void logAssert(String field, String actual, String expected) {
    if (expected.equals(actual)) {
      LOG.info("ASSERT OK  | field='{}' expected='{}' actual='{}'", field, expected, actual);
    } else {
      LOG.warn(">>>>> ASSERT MISMATCH (near miss?) | field='{}' expected='{}' actual='{}'",
          field, expected, actual);
    }
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
    private final CompletableFuture<ParsedEvent> accepted = new CompletableFuture<>();

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
      accepted.complete(receiverMan.accept("hapi-hl7", message.encode()));
      try {
          return message.generateACK();
      } catch (IOException e) {
          throw new RuntimeException("Failed to generate ACK", e);
      }
    }
  }

  private static final class HapiHl7Parser implements org.receiverman.domains.EventParser {
    private static final Logger LOG = LoggerFactory.getLogger(HapiHl7Parser.class);
    private final HapiContext context = new DefaultHapiContext();

    @Override
    public ParsedEvent parse(String raw, Instant receivedAt) {
      try {
        Message message = context.getPipeParser().parse(raw);
        Terser terser = new Terser(message);
        String type = terser.get("/MSH-9-1") + "^" + terser.get("/MSH-9-2");
        ParsedEvent event = ParsedEvent.of(raw, receivedAt, Map.of(
            "msh.messageType", type,
            "msh.messageControlId", terser.get("/MSH-10"),
            "pid.patientId", terser.get("/PID-3-1")
        ));
        LOG.debug("HapiHl7Parser parsed successfully: fields={}", event.fields());
        return event;
      } catch (HL7Exception e) {
        LOG.error(">>>>> PARSER FAILED — HapiHl7Parser could not parse HL7 message.{}Raw input: {}",
            System.lineSeparator(), raw, e);
        throw new IllegalArgumentException("Unable to parse HL7 message", e);
      }
    }
  }
}
