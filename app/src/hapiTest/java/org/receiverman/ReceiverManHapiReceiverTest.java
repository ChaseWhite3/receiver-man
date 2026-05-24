package org.receiverman;

import static org.testng.Assert.assertEquals;
import java.io.IOException;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.receiverman.descriptors.entities.ParsedEvent;
import org.receiverman.domains.FulfillmentToken;
import org.receiverman.domains.ReceiverManBus;
import org.receiverman.domains.ReceiverManIntensions;
import org.receiverman.domains.ReceiverRegistry;
import org.receiverman.domains.ReceiverSpec;
import org.receiverman.domains.Supplier;
import org.receiverman.domains.SupplierScenario;
import org.receiverman.domains.SupplierTemplate;
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
  @Test
  public void hapiSimpleServerCanFeedReceiverManBus() throws Exception {
    SupplierScenario scenario = ReceiverManIntensions.scenario("HAPI ADT flow")
        .expect("Admission received")
            .from("hapi-hl7")
            .where("msh.messageType").equalsTo("ADT^A01")
            .where("msh.messageControlId").equalsTo("MSG-1")
            .where("pid.patientId").equalsTo("P123")
            .emitName("hapi-admission-received")
            .emit("admission:{{pid.patientId}}:{{msh.messageControlId}}")
        .buildScenario();
    SupplierTemplate template = new SupplierTemplate(
        new Supplier("HAPI Sender", java.util.List.of(scenario)),
        "pid.patientId",
        java.util.List.of(new ReceiverSpec("hapi-hl7", "hapi-hl7"))
    );
    ReceiverRegistry registry = new ReceiverRegistry()
        .parser("hapi-hl7", new HapiHl7Parser());
    ReceiverManBus receiverMan = ReceiverManBus.fromTemplate(template, registry, Clock.systemUTC());
    CompletionStage<FulfillmentToken> fulfilled = receiverMan.await("HAPI ADT flow");

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

      ParsedEvent accepted = application.accepted.toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertEquals(accepted.field("msh.messageType").orElseThrow(), "ADT^A011");
      assertEquals(accepted.field("msh.messageControlId").orElseThrow(), "MSG-1");
      assertEquals(accepted.field("pid.patientId").orElseThrow(), "P123");
      assertEquals(accepted.field("receiver").orElseThrow(), "hapi-hl7");
      assertEquals(accepted.field("parser").orElseThrow(), "hapi-hl7");

      FulfillmentToken token = fulfilled.toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertEquals(token.name(), "hapi-admission-received");
      assertEquals(token.value(), "admission:P123:MSG-1");
      assertEquals(token.event().field("receiver").orElseThrow(), "hapi-hl7");
    } finally {
      server.stopAndWait();
      hapi.close();
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
    private final HapiContext context = new DefaultHapiContext();

    @Override
    public ParsedEvent parse(String raw, Instant receivedAt) {
      try {
        Message message = context.getPipeParser().parse(raw);
        Terser terser = new Terser(message);
        String type = terser.get("/MSH-9-1") + "^" + terser.get("/MSH-9-2");
        return ParsedEvent.of(raw, receivedAt, Map.of(
            "msh.messageType", type,
            "msh.messageControlId", terser.get("/MSH-10"),
            "pid.patientId", terser.get("/PID-3-1")
        ));
      } catch (HL7Exception e) {
        throw new IllegalArgumentException("Unable to parse HL7 message", e);
      }
    }
  }
}
