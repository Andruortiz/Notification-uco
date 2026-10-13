package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.utils.CorrelationId;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageReceiverContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageSenderContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;

class ObservationCorrelationFilterTest {

  private final ObservationCorrelationFilter filter = new ObservationCorrelationFilter();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private static Message messageWith(final String correlationHeader) {
    final MessageProperties properties = new MessageProperties();
    if (correlationHeader != null) {
      properties.setHeader(CorrelationId.AMQP_HEADER, correlationHeader);
    }
    return new Message("body".getBytes(StandardCharsets.UTF_8), properties);
  }

  private static String correlationOf(final Observation.Context context) {
    return context.getHighCardinalityKeyValues().stream()
        .filter(entry -> entry.getKey().equals(CorrelationId.CONTEXT_KEY))
        .map(KeyValue::getValue)
        .findFirst()
        .orElse(null);
  }

  @Test
  void theValidatedCorrelationIdOfAPublishedMessageBecomesASpanAttribute() {
    final Observation.Context context =
        new RabbitMessageSenderContext(messageWith("corr-send"), "template", "exchange");

    filter.map(context);

    assertEquals("corr-send", correlationOf(context));
  }

  @Test
  void theValidatedCorrelationIdOfAConsumedMessageBecomesASpanAttribute() {
    final Observation.Context context =
        new RabbitMessageReceiverContext(messageWith("corr-recv"), "listener");

    filter.map(context);

    assertEquals("corr-recv", correlationOf(context));
  }

  @Test
  void theCorrelationIdOfTheRestResponseBecomesASpanAttribute() {
    final MockServerHttpResponse response = new MockServerHttpResponse();
    response.getHeaders().set(CorrelationId.HEADER, "corr-rest");
    final Observation.Context context =
        new ServerRequestObservationContext(
            MockServerHttpRequest.get("/notifications").build(), response, Map.of());

    filter.map(context);

    assertEquals("corr-rest", correlationOf(context));
  }

  @Test
  void anExplicitContextEntryWinsAndAnInvalidOneIsNotAdded() {
    final Observation.Context valid = new Observation.Context();
    valid.put(CorrelationId.CONTEXT_KEY, "corr-explicit");
    final Observation.Context invalid = new Observation.Context();
    invalid.put(CorrelationId.CONTEXT_KEY, "bad id\nforged");

    filter.map(valid);
    filter.map(invalid);

    assertEquals("corr-explicit", correlationOf(valid));
    assertNull(correlationOf(invalid));
  }

  @Test
  void anInvalidHeaderIsNeverCopiedIntoTheSpan() {
    final Observation.Context context =
        new RabbitMessageSenderContext(messageWith("bad id\nforged"), "template", "exchange");

    filter.map(context);

    assertNull(correlationOf(context));
  }

  @Test
  void otherContextsFallBackToTheMdcCorrelationId() {
    MDC.put(CorrelationId.CONTEXT_KEY, "corr-mdc");
    final Observation.Context context = new Observation.Context();

    filter.map(context);

    assertEquals("corr-mdc", correlationOf(context));
  }

  @Test
  void withoutAnyCorrelationIdNoAttributeIsAdded() {
    final Observation.Context context = new Observation.Context();

    filter.map(context);

    assertNull(correlationOf(context));
  }

  @Test
  void recipientsAndTokensInOtherAttributesAreMaskedAndSafeValuesStayUntouched() {
    final Observation.Context context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("recipient", "alice@example.com"));
    context.addLowCardinalityKeyValue(KeyValue.of("provider", "brevo"));
    context.addHighCardinalityKeyValue(KeyValue.of("http.url", "/send?token=abc123secret"));
    context.addHighCardinalityKeyValue(KeyValue.of("phone", "+573001234567"));
    context.addHighCardinalityKeyValue(KeyValue.of("note", "line1\nline2"));

    filter.map(context);

    final StringBuilder all = new StringBuilder();
    context.getLowCardinalityKeyValues().forEach(entry -> all.append(entry.getValue()).append(' '));
    context
        .getHighCardinalityKeyValues()
        .forEach(entry -> all.append(entry.getValue()).append(' '));
    final String text = all.toString();
    assertFalse(text.contains("alice@"));
    assertFalse(text.contains("abc123secret"));
    assertFalse(text.contains("+573001234567"));
    assertFalse(text.contains("\n"));
    assertTrue(text.contains("brevo"));
    assertTrue(text.contains("@example.com"));
  }
}
