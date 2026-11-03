package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.AttemptRecord;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.support.ControllableProvider;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.catalog.channels.EMAIL.providers[0]=retry-email",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class NotificationRetryE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @TestConfiguration
  static class RetryProviders {

    @Bean
    ControllableProvider retryEmail() {
      return new ControllableProvider("retry-email");
    }
  }

  @LocalServerPort private int port;

  @Autowired private NotificationRepository notificationRepository;

  @Autowired private ControllableProvider retryEmail;

  private FailoverClient client;
  private WebTestClient web;

  @BeforeEach
  void setUp() {
    client = new FailoverClient(port);
    web =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    retryEmail.respondWith(AttemptResult.PERMANENT_FAILURE);
  }

  private WebTestClient.ResponseSpec retry(final String tenant, final Role role, final String id) {
    return web.post()
        .uri("/notification-retries")
        .header("Authorization", TestTokens.bearer(tenant, role))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("notificationId", id))
        .exchange();
  }

  private String failedByProvider(final String tenant, final String externalId) {
    final String id = client.accept(tenant, externalId, "EMAIL", "rec-" + externalId);
    client.awaitStatus(tenant, id, "FAILED");
    return id;
  }

  private String exhaustedNotification(final String tenant, final String externalId) {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of(tenant),
                ExternalId.of(externalId),
                ChannelType.of("EMAIL"),
                RecipientId.of("rec-" + externalId),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    final AttemptRecord record =
        AttemptRecord.of(
            AttemptResult.RECOVERABLE_FAILURE,
            AttemptOrigin.AUTOMATIC,
            ProviderId.of("retry-email"));
    notification.markQueued();
    for (int i = 0; i < 4; i++) {
      notification.markRecoverable(record);
      notification.requeue();
      notification.markQueued();
    }
    notification.markRetriesExhausted(record);
    notificationRepository.save(notification).block();
    return notification.notificationId().value();
  }

  @Test
  void aFailedNotificationIsRetriedAndDeliveredWithinFiveSecondsWithManualOrigin() {
    final String id = failedByProvider("tenant-1", "retry-ok");
    retryEmail.respondWith(AttemptResult.ACCEPTED);
    final int before = retryEmail.invocations();

    final Instant start = Instant.now();
    final Map<String, Object> accepted =
        retry("tenant-1", Role.OPERADOR, id)
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    final Map<String, Object> delivered = client.awaitStatus("tenant-1", id, "DELIVERED");
    final Duration elapsed = Duration.between(start, Instant.now());

    assertNotNull(accepted);
    assertEquals("PENDING", accepted.get("status"));
    assertEquals(id, accepted.get("notificationId"));
    assertEquals("DELIVERED", delivered.get("status"));
    assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) <= 0, "took " + elapsed);
    assertEquals(before + 1, retryEmail.invocations());
    final List<Map<String, Object>> attempts = client.attempts("tenant-1", "rec-retry-ok", id);
    assertEquals(
        List.of("AUTOMATIC", "MANUAL"), attempts.stream().map(a -> a.get("origin")).toList());
  }

  @Test
  void aManualRetryDoesNotResetTheBudgetSoAnExhaustedNotificationFailsAgain() {
    final String id = exhaustedNotification("tenant-1", "retry-budget");
    retryEmail.respondWith(AttemptResult.RECOVERABLE_FAILURE);

    retry("tenant-1", Role.OPERADOR, id).expectStatus().isAccepted();
    final Map<String, Object> after = client.awaitStatus("tenant-1", id, "FAILED");
    final Instant deadline = Instant.now().plusSeconds(10);
    List<Map<String, Object>> attempts = client.attempts("tenant-1", "rec-retry-budget", id);
    while (attempts.size() < 6 && Instant.now().isBefore(deadline)) {
      attempts = client.attempts("tenant-1", "rec-retry-budget", id);
    }

    assertEquals("FAILED", after.get("status"));
    assertEquals(6, attempts.size());
    assertEquals("MANUAL", attempts.getLast().get("origin"));
  }

  @Test
  void aStateThatDoesNotAdmitRetryGetsBadRequestAndNothingIsDispatched() {
    final String id = client.accept("tenant-1", "retry-delivered", "EMAIL", "rec-delivered");
    client.awaitStatus("tenant-1", id, "FAILED");
    retryEmail.respondWith(AttemptResult.ACCEPTED);
    retry("tenant-1", Role.OPERADOR, id).expectStatus().isAccepted();
    client.awaitStatus("tenant-1", id, "DELIVERED");
    final int before = retryEmail.invocations();

    retry("tenant-1", Role.OPERADOR, id).expectStatus().isBadRequest();

    assertEquals(before, retryEmail.invocations());
    assertEquals("DELIVERED", client.detail("tenant-1", id).get("status"));
  }

  @Test
  void anotherTenantGetsTheSameNotFoundAsAnUnknownIdAndTheOwnerIsAccepted() {
    final String id = failedByProvider("tenant-1", "retry-tenants");
    final int before = retryEmail.invocations();

    final String foreign =
        retry("tenant-2", Role.OPERADOR, id)
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    final String unknown =
        retry("tenant-2", Role.OPERADOR, "00000000-0000-0000-0000-000000000000")
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertNotNull(foreign);
    assertNotNull(unknown);
    assertEquals(
        unknown.replaceAll("[0-9a-f-]{36}", "ID").replaceAll("\"correlationId\":\"[^\"]*\"", ""),
        foreign.replaceAll("[0-9a-f-]{36}", "ID").replaceAll("\"correlationId\":\"[^\"]*\"", ""));
    assertEquals(before, retryEmail.invocations());
    assertEquals("FAILED", client.detail("tenant-1", id).get("status"));
    retry("tenant-1", Role.OPERADOR, id).expectStatus().isAccepted();
  }

  @Test
  void aClientRoleCannotRetry() {
    final String id = failedByProvider("tenant-1", "retry-role");

    retry("tenant-1", Role.CLIENTE, id).expectStatus().isForbidden();
  }

  @Test
  void twoSimultaneousRetriesProduceOneAcceptanceAndOneAttempt() throws Exception {
    final String id = failedByProvider("tenant-1", "retry-race");
    retryEmail.respondWith(AttemptResult.ACCEPTED);
    final int before = retryEmail.invocations();
    final CountDownLatch start = new CountDownLatch(1);

    final List<CompletableFuture<Integer>> calls =
        List.of(
            CompletableFuture.supplyAsync(() -> statusOfRetryAfter(start, id)),
            CompletableFuture.supplyAsync(() -> statusOfRetryAfter(start, id)));
    start.countDown();
    final List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
    client.awaitStatus("tenant-1", id, "DELIVERED");

    assertEquals(1, statuses.stream().filter(status -> status == 202).count(), statuses.toString());
    assertTrue(
        statuses.stream().filter(status -> status != 202).allMatch(s -> s == 409 || s == 400));
    assertEquals(before + 1, retryEmail.invocations());
  }

  private int statusOfRetryAfter(final CountDownLatch start, final String id) {
    try {
      start.await();
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return retry("tenant-1", Role.OPERADOR, id).returnResult(String.class).getStatus().value();
  }
}
