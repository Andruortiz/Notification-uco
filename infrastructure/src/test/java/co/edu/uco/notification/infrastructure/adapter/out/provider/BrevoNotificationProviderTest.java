package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.infrastructure.config.BrevoProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class BrevoNotificationProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static FakeBrevoServer fakeServer;
  private static WebClient webClient;

  @BeforeAll
  static void startServer() {
    fakeServer = FakeBrevoServer.start();
    webClient = WebClient.builder().baseUrl(fakeServer.baseUrl()).build();
  }

  @AfterAll
  static void stopServer() {
    fakeServer.stop();
  }

  @BeforeEach
  void resetServer() {
    fakeServer.reset();
  }

  private static Notification notificationWith(final String subject, final String body) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-" + java.util.UUID.randomUUID()),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of(subject, body), Priority.NORMAL));
  }

  private static BrevoProviderProperties enabledProperties() {
    return new BrevoProviderProperties(
        "test-api-key",
        "sender@example.com",
        "Notification UCO",
        fakeServer.baseUrl(),
        10_000L,
        5_000L);
  }

  @Test
  void sendRejectsNullNotification() {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(webClient, enabledProperties());

    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> provider.send(null));
  }

  @Test
  void declaresTheBrevoProviderId() {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(webClient, enabledProperties());

    assertEquals(ProviderId.of("brevo"), provider.providerId());
  }

  @Test
  void happyPathBuildsTheExpectedRequestAndReturnsAccepted() throws Exception {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(webClient, enabledProperties());
    final Notification notification = notificationWith("Subject", "Body");

    StepVerifier.create(provider.send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
    final FakeBrevoServer.RecordedRequest request = fakeServer.requests().get(0);
    assertEquals("/v3/smtp/email", request.path());
    assertEquals("test-api-key", request.header("api-key"));

    final JsonNode body = MAPPER.readTree(request.body());
    assertEquals("sender@example.com", body.get("sender").get("email").asText());
    assertEquals("Notification UCO", body.get("sender").get("name").asText());
    assertEquals("alice@example.com", body.get("to").get(0).get("email").asText());
    assertEquals("Subject", body.get("subject").asText());
    assertEquals("Body", body.get("textContent").asText());
    assertEquals(
        notification.notificationId().value(), body.get("headers").get("Idempotency-Key").asText());
  }

  @Test
  void disabledWithoutApiKeyNeverCallsTheProviderAndFailsBeforeAnyAttempt() {
    final BrevoProviderProperties properties =
        new BrevoProviderProperties(
            null, "sender@example.com", null, fakeServer.baseUrl(), 10_000L, 5_000L);
    final BrevoNotificationProvider provider = new BrevoNotificationProvider(webClient, properties);

    StepVerifier.create(provider.send(notificationWith("Subject", "Body")))
        .expectError(ProviderDisabledException.class)
        .verify();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void disabledConstructionLogsExactlyOneWarningNamingProviderAndReasonWithoutTheApiKey() {
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(appender);
    try {
      final BrevoProviderProperties properties =
          new BrevoProviderProperties(
              "super-secret-key", null, null, fakeServer.baseUrl(), 10_000L, 5_000L);

      new BrevoNotificationProvider(webClient, properties);

      final List<ILoggingEvent> warnings =
          appender.list.stream()
              .filter(event -> event.getLevel().toString().equals("WARN"))
              .toList();
      assertEquals(1, warnings.size());
      final String message = warnings.get(0).getFormattedMessage();
      assertTrue(message.contains("brevo"));
      assertTrue(message.contains("sender-email"));
      assertTrue(!message.contains("super-secret-key"));
    } finally {
      rootLogger.detachAppender(appender);
    }
  }

  @Test
  void disabledWithoutSenderEmailNeverCallsTheProvider() {
    final BrevoProviderProperties properties =
        new BrevoProviderProperties(
            "test-api-key", null, null, fakeServer.baseUrl(), 10_000L, 5_000L);
    final BrevoNotificationProvider provider = new BrevoNotificationProvider(webClient, properties);

    StepVerifier.create(provider.send(notificationWith("Subject", "Body")))
        .expectError(ProviderDisabledException.class)
        .verify();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void missingSubjectReturnsPermanentFailureWithoutCallingProvider() {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(webClient, enabledProperties());

    StepVerifier.create(provider.send(notificationWith(null, "Body")))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void blankSubjectReturnsPermanentFailureWithoutCallingProvider() {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(webClient, enabledProperties());

    StepVerifier.create(provider.send(notificationWith("   ", "Body")))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void requestTimesOutWithinTheConfiguredDuration() {
    final WebClient shortTimeoutClient =
        WebClient.builder()
            .baseUrl(fakeServer.baseUrl())
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(Duration.ofMillis(300))))
            .build();
    final BrevoProviderProperties properties =
        new BrevoProviderProperties(
            "test-api-key", "sender@example.com", null, fakeServer.baseUrl(), 300L, 5_000L);
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(shortTimeoutClient, properties);
    fakeServer.nextDelay(3_000);

    final Instant start = Instant.now();
    StepVerifier.create(provider.send(notificationWith("Subject", "Body")))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();
    final Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "elapsed was " + elapsed);
  }

  @Test
  void idempotencyKeyIsStableForTheSameNotificationAndDistinctBetweenNotifications()
      throws Exception {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(webClient, enabledProperties());
    final Notification first = notificationWith("Subject", "Body");
    final Notification second = notificationWith("Subject", "Body");

    StepVerifier.create(provider.send(first)).expectNext(AttemptResult.ACCEPTED).verifyComplete();
    StepVerifier.create(provider.send(first)).expectNext(AttemptResult.ACCEPTED).verifyComplete();
    StepVerifier.create(provider.send(second)).expectNext(AttemptResult.ACCEPTED).verifyComplete();

    final JsonNode firstAttempt1 = MAPPER.readTree(fakeServer.requests().get(0).body());
    final JsonNode firstAttempt2 = MAPPER.readTree(fakeServer.requests().get(1).body());
    final JsonNode secondAttempt = MAPPER.readTree(fakeServer.requests().get(2).body());

    final String key1 = firstAttempt1.get("headers").get("Idempotency-Key").asText();
    final String key2 = firstAttempt2.get("headers").get("Idempotency-Key").asText();
    final String key3 = secondAttempt.get("headers").get("Idempotency-Key").asText();

    assertEquals(key1, key2);
    assertNotEquals(key1, key3);
  }
}
