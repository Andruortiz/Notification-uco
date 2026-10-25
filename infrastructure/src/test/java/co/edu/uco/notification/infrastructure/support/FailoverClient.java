package co.edu.uco.notification.infrastructure.support;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

public final class FailoverClient {

  private final WebTestClient client;

  public FailoverClient(final int port) {
    this.client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
  }

  public String accept(
      final String tenantId,
      final String externalId,
      final String channelType,
      final String recipientId) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (true) {
      final EntityExchangeResult<Map<String, Object>> result =
          client
              .post()
              .uri("/notifications")
              .header("Authorization", TestTokens.bearer(tenantId))
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(
                  Map.of(
                      "externalId", externalId,
                      "channelType", channelType,
                      "recipientId", recipientId,
                      "recipientAddress", "alice@example.com",
                      "subject", "Subject",
                      "body", "Body",
                      "priority", "NORMAL"))
              .exchange()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult();
      if (result.getStatus().value() == 202) {
        assertNotNull(result.getResponseBody());
        return result.getResponseBody().get("notificationId").toString();
      }
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError(
            "accept failed: " + result.getStatus() + " " + result.getResponseBody());
      }
      sleep();
    }
  }

  public Map<String, Object> detail(final String tenantId, final String notificationId) {
    return client
        .get()
        .uri("/notifications/detail?notificationId={id}", notificationId)
        .header("Authorization", TestTokens.bearer(tenantId))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  public Map<String, Object> awaitStatus(
      final String tenantId, final String notificationId, final String expectedStatus) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
    Map<String, Object> current = detail(tenantId, notificationId);
    while (Instant.now().isBefore(deadline)
        && !expectedStatus.equals(String.valueOf(current.get("status")))) {
      sleep();
      current = detail(tenantId, notificationId);
    }
    return current;
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> attempts(
      final String tenantId, final String recipientId, final String notificationId) {
    final Map<String, Object> page =
        client
            .get()
            .uri("/notifications?recipientId={r}&limit=100", recipientId)
            .header("Authorization", TestTokens.bearer(tenantId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(page);
    final List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    return items.stream()
        .filter(item -> notificationId.equals(item.get("notificationId")))
        .findFirst()
        .map(item -> (List<Map<String, Object>>) item.get("deliveryAttempts"))
        .orElse(List.of());
  }

  @SuppressWarnings("unchecked")
  public int itemCount(final String tenantId, final String recipientId) {
    final Map<String, Object> page =
        client
            .get()
            .uri("/notifications?recipientId={r}&limit=100", recipientId)
            .header("Authorization", TestTokens.bearer(tenantId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(page);
    return ((List<Map<String, Object>>) page.get("items")).size();
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
