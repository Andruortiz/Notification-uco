package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import co.edu.uco.notification.infrastructure.support.ControllableProvider;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
      "notification.catalog.channels.EMAIL.providers[0]=resend-email",
      "notification.catalog.channels.SMS.providers[0]=resend-sms",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class NotificationResendE2ETest {

  static final String PHONE = "+573001234567";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @TestConfiguration
  static class ResendProviders {

    @Bean
    ControllableProvider resendEmail() {
      return new ControllableProvider("resend-email");
    }

    @Bean
    ControllableProvider resendSms() {
      return new ControllableProvider("resend-sms");
    }
  }

  @LocalServerPort private int port;

  @Autowired private ControllableProvider resendEmail;

  @Autowired private ControllableProvider resendSms;

  private FailoverClient client;
  private WebTestClient web;
  private ConcurrentListAppender appender;

  @BeforeEach
  void setUp() {
    client = new FailoverClient(port);
    web =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    resendEmail.respondWith(AttemptResult.PERMANENT_FAILURE);
    resendSms.respondWith(AttemptResult.ACCEPTED);
    appender = new ConcurrentListAppender();
    appender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
    appender.stop();
  }

  private WebTestClient.ResponseSpec resend(
      final String tenant, final Role role, final String id, final String channel) {
    return web.post()
        .uri("/notification-resends")
        .header("Authorization", TestTokens.bearer(tenant, role))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("notificationId", id, "channelType", channel, "recipient", PHONE))
        .exchange();
  }

  private String failedEmail(final String tenant, final String externalId) {
    final String id = client.accept(tenant, externalId, "EMAIL", "rec-" + externalId);
    client.awaitStatus(tenant, id, "FAILED");
    return id;
  }

  private static String normalized(final String body) {
    return body.replaceAll("[0-9a-f-]{36}", "ID").replaceAll("\"correlationId\":\"[^\"]*\"", "");
  }

  @Test
  void aFailedEmailIsResentBySmsAndDeliveredWithinFiveSecondsLeavingTheOriginalUntouched() {
    final String original = failedEmail("tenant-1", "resend-ok");
    final int smsBefore = resendSms.invocations();

    final Instant start = Instant.now();
    final Map<String, Object> accepted =
        resend("tenant-1", Role.OPERADOR, original, "SMS")
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(accepted);
    final String resent = accepted.get("notificationId").toString();
    final Map<String, Object> delivered = client.awaitStatus("tenant-1", resent, "DELIVERED");
    final Duration elapsed = Duration.between(start, Instant.now());

    assertEquals("PENDING", accepted.get("status"));
    assertEquals("SMS", accepted.get("channelType"));
    assertNotEquals(original, resent);
    assertEquals("DELIVERED", delivered.get("status"));
    assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) <= 0, "took " + elapsed);
    assertEquals("FAILED", client.detail("tenant-1", original).get("status"));
    assertEquals(1, client.attempts("tenant-1", "rec-resend-ok", original).size());
    assertEquals(smsBefore + 1, resendSms.invocations());
    assertFalse(accepted.toString().contains(PHONE));
    assertFalse(loggedText().contains(PHONE));
  }

  @Test
  void repeatingTheResendOnTheSameChannelIsAConflictAndCreatesNothing() {
    final String original = failedEmail("tenant-1", "resend-twice");
    final int before = resendSms.invocations();
    resend("tenant-1", Role.OPERADOR, original, "SMS").expectStatus().isAccepted();

    final String body =
        resend("tenant-1", Role.OPERADOR, original, "SMS")
            .expectStatus()
            .isEqualTo(409)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertNotNull(body);
    assertFalse(body.contains(PHONE));
    assertEquals(2, client.itemCount("tenant-1", "rec-resend-twice"));
    awaitInvocations(before + 1);
    assertEquals(before + 1, resendSms.invocations());
  }

  @Test
  void invalidResendsAreBadRequestWithoutCreatingAnything() {
    final String original = failedEmail("tenant-1", "resend-invalid");
    final String delivered = client.accept("tenant-1", "resend-delivered", "SMS", "rec-d");
    client.awaitStatus("tenant-1", delivered, "DELIVERED");
    final int before = resendSms.invocations();

    resend("tenant-1", Role.OPERADOR, original, "EMAIL").expectStatus().isBadRequest();
    resend("tenant-1", Role.OPERADOR, original, "FAX").expectStatus().isBadRequest();
    resend("tenant-1", Role.OPERADOR, delivered, "EMAIL").expectStatus().isBadRequest();

    assertEquals(before, resendSms.invocations());
    assertEquals(1, client.itemCount("tenant-1", "rec-resend-invalid"));
  }

  @Test
  void anotherTenantGetsTheSameNotFoundAsAnUnknownIdAndTheClientRoleIsForbidden() {
    final String original = failedEmail("tenant-1", "resend-tenants");
    final int smsBefore = resendSms.invocations();

    final String foreign =
        resend("tenant-2", Role.OPERADOR, original, "SMS")
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    final String unknown =
        resend("tenant-2", Role.OPERADOR, "00000000-0000-0000-0000-000000000000", "SMS")
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertNotNull(foreign);
    assertNotNull(unknown);
    assertEquals(normalized(unknown), normalized(foreign));
    assertEquals(smsBefore, resendSms.invocations());
    resend("tenant-1", Role.CLIENTE, original, "SMS").expectStatus().isForbidden();
    resend("tenant-1", Role.OPERADOR, original, "SMS").expectStatus().isAccepted();
  }

  @Test
  void twoSimultaneousResendsToTheSameChannelCreateOneNotification() {
    final String original = failedEmail("tenant-1", "resend-race");
    final int before = resendSms.invocations();
    final CountDownLatch start = new CountDownLatch(1);

    final List<CompletableFuture<Integer>> calls =
        List.of(
            CompletableFuture.supplyAsync(() -> statusOfResendAfter(start, original)),
            CompletableFuture.supplyAsync(() -> statusOfResendAfter(start, original)));
    start.countDown();
    final List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
    awaitInvocations(before + 1);

    assertEquals(1, statuses.stream().filter(status -> status == 202).count(), statuses.toString());
    assertEquals(1, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
    assertEquals(before + 1, resendSms.invocations());
  }

  private int statusOfResendAfter(final CountDownLatch start, final String original) {
    try {
      start.await();
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return resend("tenant-1", Role.OPERADOR, original, "SMS")
        .returnResult(String.class)
        .getStatus()
        .value();
  }

  private void awaitInvocations(final int expected) {
    final Instant deadline = Instant.now().plusSeconds(10);
    while (resendSms.invocations() < expected && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(100);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private String loggedText() {
    final StringBuilder text = new StringBuilder();
    for (final ILoggingEvent event : appender.list) {
      text.append(event.getFormattedMessage())
          .append(event.getMDCPropertyMap())
          .append(event.getMarkerList())
          .append(event.getKeyValuePairs())
          .append(event.getThrowableProxy());
    }
    return text.toString();
  }
}
