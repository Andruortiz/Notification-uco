package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.usecase.ConfigurationHolder;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.provider.brevo.api-key=e2e-test-secret-key",
      "notification.provider.brevo.sender-email=sender@example.com",
      "notification.provider.brevo.timeout-ms=5000",
      "notification.provider.brevo.connect-timeout-ms=5000"
    })
@Testcontainers
class ConfigurationProviderTimeoutsE2ETest {

  private static final long SLOW_RESPONSE_MS = 2_500;
  private static final long NEW_TIMEOUT_MS = 1_000;
  private static final Duration MARGIN = Duration.ofMillis(700);

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer FAKE_BREVO;

  @DynamicPropertySource
  static void brevoBaseUrl(final DynamicPropertyRegistry registry) {
    FAKE_BREVO = FakeProviderServer.start();
    registry.add("notification.provider.brevo.base-url", () -> FAKE_BREVO.baseUrl());
  }

  @AfterAll
  static void stopFakeBrevo() {
    FAKE_BREVO.stop();
  }

  @Autowired private ConfigurationHolder configurationHolder;

  @Autowired private BrevoNotificationProvider brevoProvider;

  private ConfigurationSnapshot initial;

  @BeforeEach
  void restoreInitialConfiguration() {
    FAKE_BREVO.reset();
    if (initial == null) {
      initial = configurationHolder.snapshot();
    }
    configurationHolder.replace(initial);
  }

  private static Notification notification() {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-" + UUID.randomUUID()),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL));
  }

  private void publishBrevoTimeout(final long timeoutMs) {
    final ConfigurationSnapshot current = configurationHolder.snapshot();
    final Map<String, Long> values = new HashMap<>(current.values());
    values.put(ParameterRegistry.timeoutKey("brevo"), timeoutMs);
    configurationHolder.replace(
        new ConfigurationSnapshot(
            current.version() + 1,
            current.source(),
            values,
            Instant.now(),
            current.pendingRestart()));
  }

  @Test
  void beforeAnyChangeASlowCallIsWithinTheStartupTimeout() {
    FAKE_BREVO.nextDelay(SLOW_RESPONSE_MS);

    final AttemptResult result = brevoProvider.send(notification()).block(Duration.ofSeconds(15));

    assertEquals(AttemptResult.ACCEPTED, result);
  }

  @Test
  void aTimeoutChangeDuringACallDoesNotAffectItAndTheNextCallUsesTheNewValue() throws Exception {
    FAKE_BREVO.nextDelay(SLOW_RESPONSE_MS);
    final Instant start = Instant.now();
    final var inFlight = brevoProvider.send(notification()).toFuture();
    while (FAKE_BREVO.requests().isEmpty()) {
      assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0);
      Thread.sleep(20);
    }

    publishBrevoTimeout(NEW_TIMEOUT_MS);

    assertEquals(AttemptResult.ACCEPTED, inFlight.get());
    assertTrue(
        Duration.between(start, Instant.now()).compareTo(Duration.ofMillis(SLOW_RESPONSE_MS - 200))
            >= 0);

    final Instant nextStart = Instant.now();
    final AttemptResult next = brevoProvider.send(notification()).block(Duration.ofSeconds(15));
    final Duration elapsed = Duration.between(nextStart, Instant.now());

    assertEquals(AttemptResult.RECOVERABLE_FAILURE, next);
    assertTrue(
        elapsed.compareTo(Duration.ofMillis(NEW_TIMEOUT_MS).plus(MARGIN)) < 0,
        "elapsed was " + elapsed);
  }
}
