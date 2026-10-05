package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
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
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.infrastructure.support.FakeParametersSource;
import co.edu.uco.notification.infrastructure.support.FakeParametersSourceConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
      "notification.parameters.poll-interval-ms=1000",
      "notification.provider.brevo.api-key=e2e-test-secret-key",
      "notification.provider.brevo.sender-email=sender@example.com",
      "notification.provider.brevo.timeout-ms=5000",
      "notification.provider.brevo.connect-timeout-ms=5000"
    })
@Testcontainers
@Import(FakeParametersSourceConfig.class)
class ConfigurationProviderTimeoutsE2ETest {

  private static final long SLOW_RESPONSE_MS = 4_000;
  private static final long NEW_TIMEOUT_MS = 1_000;
  private static final Duration MARGIN = Duration.ofMillis(700);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration ADOPTION_MARGIN = Duration.ofSeconds(2);
  private static final Duration ADOPTION_LIMIT = Duration.ofSeconds(15);
  private static final long STARTUP_TIMEOUT_MS = 5_000;

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

  @Autowired private ConfigurationView configurationView;

  @Autowired private FakeParametersSource fakeSource;

  @Autowired private BrevoNotificationProvider brevoProvider;

  @BeforeEach
  void restoreInitialConfiguration() {
    FAKE_BREVO.reset();
    fakeSource.publishNextAndAwaitAdoption(
        configurationView, brevoTimeout(STARTUP_TIMEOUT_MS), ADOPTION_LIMIT);
    assertEquals(STARTUP_TIMEOUT_MS, configurationView.snapshot().providerTimeoutMs("brevo"));
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

  private static Map<String, Object> brevoTimeout(final long timeoutMs) {
    return Map.of(ParameterRegistry.timeoutKey("brevo"), timeoutMs);
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

    final Duration adoption =
        fakeSource.publishNextAndAwaitAdoption(
            configurationView, brevoTimeout(NEW_TIMEOUT_MS), ADOPTION_LIMIT);
    assertEquals(NEW_TIMEOUT_MS, configurationView.snapshot().providerTimeoutMs("brevo"));
    assertTrue(
        adoption.compareTo(POLL_INTERVAL.plus(ADOPTION_MARGIN)) < 0, "adoption took " + adoption);

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
