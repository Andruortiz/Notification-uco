package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import co.edu.uco.notification.utils.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

class StructuredLogLayoutTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PrintStream originalOut;
  private ByteArrayOutputStream captured;
  private LoggerContext context;

  @BeforeEach
  void setUp() throws Exception {
    originalOut = System.out;
    captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    context = new LoggerContext();
    context.setMDCAdapter(MDC.getMDCAdapter());
    final URL config = getClass().getClassLoader().getResource("logback-spring.xml");
    final JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    configurator.doConfigure(config);
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    context.stop();
    MDC.clear();
  }

  private List<JsonNode> flushedLines() throws Exception {
    context.stop();
    final String output = captured.toString(StandardCharsets.UTF_8).strip();
    return output.lines().filter(line -> !line.isBlank()).map(this::parse).toList();
  }

  private JsonNode parse(final String line) {
    try {
      return MAPPER.readTree(line);
    } catch (final Exception e) {
      throw new AssertionError("log line is not valid JSON: " + line, e);
    }
  }

  @Test
  void everyEntryIsASingleLineOfParseableJsonWithTheMdcFieldsAsIndependentKeys() throws Exception {
    final Logger logger = context.getLogger("test.layout");
    try (LogContext ignored = LogContext.open(CorrelationId.of("corr-1"), "tenant-a", "n-1")) {
      logger.info("Notification accepted");
    }
    logger.info("outside of any notification");

    final List<JsonNode> lines = flushedLines();

    assertEquals(2, lines.size());
    final JsonNode first = lines.get(0);
    assertEquals("Notification accepted", first.get("message").asText());
    assertEquals("INFO", first.get("level").asText());
    assertEquals("corr-1", first.get("correlationId").asText());
    assertEquals("tenant-a", first.get("tenantId").asText());
    assertEquals("n-1", first.get("notificationId").asText());
    assertEquals("notification-service", first.get("service").asText());
    assertTrue(first.has("@timestamp"));
    assertFalse(lines.get(1).has("correlationId"));
  }

  @Test
  void traceparentInTheMdcIsEmittedAsItsOwnField() throws Exception {
    final String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    MDC.put("traceparent", traceparent);

    context.getLogger("test.layout").info("with trace");

    assertEquals(traceparent, flushedLines().get(0).get("traceparent").asText());
  }

  @Test
  void aMessageWithNewlinesStaysOnOneLine() throws Exception {
    context.getLogger("test.layout").info("first\nsecond");

    final List<JsonNode> lines = flushedLines();

    assertEquals(1, lines.size());
    assertEquals("first\nsecond", lines.get(0).get("message").asText());
  }

  @Test
  void stackTracesAreScrubbedOfSensitiveValues() throws Exception {
    final String email = "usuario.secreto@ejemplo.com";
    final String apiKey = "sk-live-0123456789";
    context
        .getLogger("test.layout")
        .error(
            "provider failed",
            new IllegalStateException(
                "rejected "
                    + email
                    + " with api-key="
                    + apiKey
                    + " Authorization: Bearer abc.def"));

    final JsonNode entry = flushedLines().get(0);

    final String trace = entry.get("stack_trace").asText();
    assertTrue(trace.contains("IllegalStateException"));
    assertFalse(trace.contains("usuario.secreto"));
    assertFalse(trace.contains(apiKey));
    assertFalse(trace.contains("abc.def"));
  }
}
