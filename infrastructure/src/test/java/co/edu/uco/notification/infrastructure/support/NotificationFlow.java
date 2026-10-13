package co.edu.uco.notification.infrastructure.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

public final class NotificationFlow {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WebTestClient api;

  public NotificationFlow(final WebTestClient api) {
    this.api = api;
  }

  public static WebTestClient client(final int port, final Duration timeout) {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(timeout)
        .build();
  }

  public String accept(
      final String tenant,
      final String externalId,
      final String correlationId,
      final String traceparent) {
    final WebTestClient.RequestBodySpec request =
        api.post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(tenant))
            .contentType(MediaType.APPLICATION_JSON);
    if (correlationId != null) {
      request.header("X-Correlation-Id", correlationId);
    }
    if (traceparent != null) {
      request.header("traceparent", traceparent);
    }
    final EntityExchangeResult<byte[]> result =
        request
            .bodyValue(
                Map.of(
                    "externalId",
                    externalId,
                    "channelType",
                    "EMAIL",
                    "recipientId",
                    "recipient-1",
                    "recipientAddress",
                    "trace-recipient@secret.invalid",
                    "subject",
                    "Subject",
                    "body",
                    "Body",
                    "priority",
                    "NORMAL"))
            .exchange()
            .expectStatus()
            .isEqualTo(202)
            .expectBody()
            .returnResult();
    try {
      return MAPPER.readTree(result.getResponseBody()).get("notificationId").asText();
    } catch (final java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public void awaitStatus(
      final String tenant, final String id, final String expected, final Duration limit)
      throws Exception {
    final Instant deadline = Instant.now().plus(limit);
    String status = null;
    while (Instant.now().isBefore(deadline)) {
      final EntityExchangeResult<byte[]> result =
          api.get()
              .uri("/notifications/" + id)
              .header("Authorization", TestTokens.bearer(tenant))
              .exchange()
              .expectBody()
              .returnResult();
      final JsonNode node = MAPPER.readTree(result.getResponseBody());
      status = node.has("status") ? node.get("status").asText() : null;
      if (expected.equals(status)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("status never reached " + expected + ", last " + status);
  }
}
