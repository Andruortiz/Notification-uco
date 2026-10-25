package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.catalog.channels.EMAIL.providers[0]=bounce-email",
      "notification.catalog.channels.SMS.providers[0]=bounce-sms",
      "notification.brevo.webhook-secret=" + RecipientValidationE2ETest.SECRET,
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class RecipientValidationE2ETest {

  static final String SECRET = "e2e-webhook-secret";
  static final String SECRET_HEADER = "X-Brevo-Webhook-Secret";
  static final String ADDRESS = "Bounced.User@Example.com";
  static final String PHONE = "+573001234567";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final class MessageIdProvider implements NotificationSenderPort {

    private final ProviderId providerId;
    private final AtomicInteger invocations = new AtomicInteger();

    MessageIdProvider(final String providerId) {
      this.providerId = ProviderId.of(providerId);
    }

    int invocations() {
      return invocations.get();
    }

    @Override
    public Mono<ProviderResult> send(final Notification notification) {
      invocations.incrementAndGet();
      return Mono.just(
          new ProviderResult(
              AttemptResult.ACCEPTED,
              null,
              null,
              "msg-" + notification.notificationId().value(),
              null));
    }

    @Override
    public ProviderId providerId() {
      return providerId;
    }

    @Override
    public Optional<String> disabledReason() {
      return Optional.empty();
    }

    @Override
    public boolean supportsAttachments() {
      return true;
    }
  }

  @TestConfiguration
  static class Providers {

    @Bean
    MessageIdProvider bounceEmail() {
      return new MessageIdProvider("bounce-email");
    }

    @Bean
    MessageIdProvider bounceSms() {
      return new MessageIdProvider("bounce-sms");
    }
  }

  @LocalServerPort private int port;

  @Autowired private MessageIdProvider bounceEmail;

  @Autowired private MessageIdProvider bounceSms;

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
    appender = new ConcurrentListAppender();
    appender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
    appender.stop();
  }

  private WebTestClient.ResponseSpec send(
      final String tenant, final String externalId, final String channel, final String address) {
    return web.post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer(tenant))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "externalId",
                externalId,
                "channelType",
                channel,
                "recipientId",
                "rec-" + externalId,
                "recipientAddress",
                address,
                "subject",
                "Subject",
                "body",
                "Body",
                "priority",
                "NORMAL"))
        .exchange();
  }

  private WebTestClient.ResponseSpec bounce(
      final String secret, final String event, final String messageId) {
    final WebTestClient.RequestBodySpec request =
        web.post().uri("/providers/brevo/bounce-events").contentType(MediaType.APPLICATION_JSON);
    if (secret != null) {
      request.header(SECRET_HEADER, secret);
    }
    return request
        .bodyValue(Map.of("event", event, "message-id", messageId, "email", ADDRESS))
        .exchange();
  }

  private WebTestClient.ResponseSpec markCall(
      final String path, final String tenant, final Role role, final String address) {
    return web.post()
        .uri(path)
        .header("Authorization", TestTokens.bearer(tenant, role))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("channel", "EMAIL", "address", address))
        .exchange();
  }

  private Map<String, Object> lookup(final String tenant, final Role role) {
    return markCall("/recipients/undeliverable-lookups", tenant, role, ADDRESS)
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private String deliveredEmail(final String tenant, final String externalId) {
    final String id =
        send(tenant, externalId, "EMAIL", ADDRESS)
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody()
            .get("notificationId")
            .toString();
    assertEquals("DELIVERED", client.awaitStatus(tenant, id, "DELIVERED").get("status"));
    return id;
  }

  @Test
  void malformedAddressesAreRejectedBeforeAnythingIsPersistedOrDispatched() {
    final int emailBefore = bounceEmail.invocations();
    final int smsBefore = bounceSms.invocations();

    final String smsBody =
        send("tenant-fmt", "fmt-sms", "SMS", "3001234567")
            .expectStatus()
            .isBadRequest()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    send("tenant-fmt", "fmt-mail", "EMAIL", "not-an-email").expectStatus().isBadRequest();

    assertNotNull(smsBody);
    assertTrue(smsBody.contains("NTF-1006"), smsBody);
    assertFalse(smsBody.contains("3001234567"));
    assertEquals(0, client.itemCount("tenant-fmt", "rec-fmt-sms"));
    assertEquals(0, client.itemCount("tenant-fmt", "rec-fmt-mail"));
    assertEquals(emailBefore, bounceEmail.invocations());
    assertEquals(smsBefore, bounceSms.invocations());
    assertFalse(loggedText().contains("not-an-email"));
  }

  @Test
  void validAddressesStillFlowAndABatchRejectsOnlyTheInvalidItems() {
    send("tenant-batch", "ok-single", "SMS", PHONE).expectStatus().isAccepted();

    final Map<String, Object> body =
        web.post()
            .uri("/notifications/batches")
            .header("Authorization", TestTokens.bearer("tenant-batch"))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of(
                    "batchId",
                    "batch-1",
                    "items",
                    List.of(
                        item("b-good", "SMS", PHONE),
                        item("b-bad", "SMS", "12345"),
                        item("b-mail", "EMAIL", "ok@example.com"))))
            .exchange()
            .expectStatus()
            .is2xxSuccessful()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertNotNull(body);
    final String text = body.toString();
    assertTrue(text.contains("rejectionReason=INVALID_RECIPIENT"), text);
    assertEquals(2, text.split("outcome=ACCEPTED", -1).length - 1, text);
    assertFalse(text.contains("12345"));
  }

  private static Map<String, Object> item(
      final String externalId, final String channel, final String address) {
    return Map.of(
        "externalId",
        externalId,
        "channelType",
        channel,
        "recipientId",
        "rec-" + externalId,
        "recipientAddress",
        address,
        "subject",
        "Subject",
        "body",
        "Body",
        "priority",
        "NORMAL");
  }

  @Test
  void aHardBounceBlocksTheNextSendWithoutCallingTheProviderAndTheAdminCanLiftIt() {
    final String original = deliveredEmail("tenant-a", "bounce-1");
    final String messageId = "msg-" + original;

    bounce(null, "hard_bounce", messageId).expectStatus().isUnauthorized();
    bounce("wrong-secret", "hard_bounce", messageId)
        .expectStatus()
        .isUnauthorized()
        .expectBody(String.class)
        .value(response -> assertTrue(response.contains("NTF-2005"), response));
    bounce(SECRET, "soft_bounce", messageId).expectStatus().isOk();
    bounce(SECRET, "deferred", messageId).expectStatus().isOk();
    assertFalse((Boolean) lookup("tenant-a", Role.OPERADOR).get("marked"));
    deliveredEmail("tenant-a", "bounce-control");

    bounce(SECRET, "hard_bounce", "msg-unknown").expectStatus().isOk();
    assertFalse((Boolean) lookup("tenant-a", Role.OPERADOR).get("marked"));

    bounce(SECRET, "hard_bounce", messageId).expectStatus().isOk();
    final Map<String, Object> marked = lookup("tenant-a", Role.OPERADOR);
    bounce(SECRET, "hard_bounce", messageId).expectStatus().isOk();
    final Map<String, Object> replayed = lookup("tenant-a", Role.OPERADOR);

    assertEquals(true, marked.get("marked"));
    assertEquals("hard_bounce", marked.get("reason"));
    assertEquals("b***@example.com", marked.get("address"));
    assertEquals(marked.get("markedAt"), replayed.get("markedAt"));
    assertEquals("bounced.user@example.com", lookup("tenant-a", Role.ADMINISTRADOR).get("address"));

    final int before = bounceEmail.invocations();
    final Instant start = Instant.now();
    final String blocked =
        send("tenant-a", "bounce-2", "EMAIL", "  BOUNCED.user@example.com ")
            .expectStatus()
            .isEqualTo(409)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(5)) <= 0);
    assertNotNull(blocked);
    assertTrue(blocked.contains("NTF-3040"), blocked);
    assertFalse(blocked.toLowerCase().contains("bounced.user"));
    assertEquals(0, client.itemCount("tenant-a", "rec-bounce-2"));
    assertEquals(before, bounceEmail.invocations());

    markCall("/recipients/undeliverable-releases", "tenant-a", Role.OPERADOR, ADDRESS)
        .expectStatus()
        .isForbidden();
    markCall("/recipients/undeliverable-lookups", "tenant-a", Role.CLIENTE, ADDRESS)
        .expectStatus()
        .isForbidden();
    send("tenant-a", "bounce-3", "EMAIL", ADDRESS).expectStatus().isEqualTo(409);

    markCall("/recipients/undeliverable-releases", "tenant-a", Role.ADMINISTRADOR, ADDRESS)
        .expectStatus()
        .isNoContent();
    markCall("/recipients/undeliverable-releases", "tenant-a", Role.ADMINISTRADOR, ADDRESS)
        .expectStatus()
        .isNoContent();
    assertFalse((Boolean) lookup("tenant-a", Role.OPERADOR).get("marked"));
    send("tenant-a", "bounce-4", "EMAIL", ADDRESS).expectStatus().isAccepted();

    final String logs = loggedText();
    assertFalse(logs.toLowerCase().contains("bounced.user"));
    assertFalse(logs.contains(SECRET));
  }

  @Test
  void
      anUnauthenticatedHugeBodyIsRejectedAndTheSimulatedPrefixIsIgnoredWhileTheSimulatedProviderIsDisabled() {
    web.post()
        .uri("/providers/brevo/bounce-events")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"event\":\"hard_bounce\",\"junk\":\"" + "x".repeat(3_000_000))
        .exchange()
        .expectStatus()
        .isUnauthorized();

    final String id = deliveredEmail("tenant-sim", "sim-1");
    bounce(SECRET, "hard_bounce", "simulated-" + id).expectStatus().isOk();

    assertFalse((Boolean) lookup("tenant-sim", Role.OPERADOR).get("marked"));
  }

  @Test
  void aBounceOfOneTenantNeverAffectsAnotherTenantSendingToTheSameAddress() {
    final String first = deliveredEmail("tenant-x", "iso-x");
    deliveredEmail("tenant-y", "iso-y");

    bounce(SECRET, "hard_bounce", "msg-" + first).expectStatus().isOk();

    assertEquals(true, lookup("tenant-x", Role.OPERADOR).get("marked"));
    assertEquals(false, lookup("tenant-y", Role.OPERADOR).get("marked"));
    send("tenant-x", "iso-x2", "EMAIL", ADDRESS).expectStatus().isEqualTo(409);
    send("tenant-y", "iso-y2", "EMAIL", ADDRESS).expectStatus().isAccepted();
    markCall("/recipients/undeliverable-releases", "tenant-y", Role.ADMINISTRADOR, ADDRESS)
        .expectStatus()
        .isNoContent();
    assertEquals(true, lookup("tenant-x", Role.OPERADOR).get("marked"));
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
