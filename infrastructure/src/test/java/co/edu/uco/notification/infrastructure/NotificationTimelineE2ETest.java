package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
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
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import co.edu.uco.notification.infrastructure.support.ControllableProvider;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
      "notification.catalog.channels.EMAIL.providers[0]=timeline-a",
      "notification.catalog.channels.EMAIL.providers[1]=timeline-raw",
      "notification.catalog.channels.EMAIL.providers[2]=simulated",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class NotificationTimelineE2ETest {

  static final String RAW_PROVIDER_TEXT = "RAW-PROVIDER-TEXT-smtp-550-mailbox-secret-7f3a";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @TestConfiguration
  static class TimelineProviders {

    @Bean
    ControllableProvider timelineA() {
      return new ControllableProvider("timeline-a");
    }

    @Bean
    NotificationSenderPort timelineRaw() {
      return new NotificationSenderPort() {
        @Override
        public Mono<ProviderResult> send(final Notification notification) {
          return Mono.error(new IllegalStateException(RAW_PROVIDER_TEXT));
        }

        @Override
        public ProviderId providerId() {
          return ProviderId.of("timeline-raw");
        }

        @Override
        public Optional<String> disabledReason() {
          return Optional.empty();
        }

        @Override
        public boolean supportsAttachments() {
          return true;
        }
      };
    }
  }

  @LocalServerPort private int port;

  @Autowired private NotificationRepository notificationRepository;

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

  private WebTestClient.ResponseSpec timeline(
      final String tenant, final Role role, final String id) {
    return web.get()
        .uri("/notifications/timeline?notificationId={id}", id)
        .header("Authorization", TestTokens.bearer(tenant, role))
        .exchange();
  }

  private Map<String, Object> timelineBody(final String tenant, final Role role, final String id) {
    return timeline(tenant, role, id)
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> attempts(final Map<String, Object> body) {
    return (List<Map<String, Object>>) body.get("attempts");
  }

  private String deliveredNotification(final String tenant, final String externalId) {
    final String id = client.accept(tenant, externalId, "EMAIL", "recipient-" + externalId);
    client.awaitStatus(tenant, id, "DELIVERED");
    return id;
  }

  @Test
  void theTimelineShowsEveryAttemptWithCauseLatencyAndCorrelationIdAndNeverTheRawProviderText() {
    final String id = deliveredNotification("tenant-1", "timeline-1");

    final Map<String, Object> body = timelineBody("tenant-1", Role.ADMINISTRADOR, id);
    final List<Map<String, Object>> attempts = attempts(body);

    assertEquals(id, body.get("notificationId"));
    assertEquals("DELIVERED", body.get("status"));
    assertNotNull(body.get("correlationId"));
    assertEquals(3, attempts.size());
    assertEquals("timeline-a", attempts.get(0).get("providerId"));
    assertEquals("RECOVERABLE_FAILURE", attempts.get(0).get("result"));
    assertEquals("PROVIDER_TEMPORARY", attempts.get(0).get("failureCause"));
    assertNotNull(attempts.get(0).get("latencyMillis"));
    assertEquals("timeline-raw", attempts.get(1).get("providerId"));
    assertEquals("TRANSPORT_ERROR", attempts.get(1).get("failureCause"));
    assertEquals("simulated", attempts.get(2).get("providerId"));
    assertEquals("ACCEPTED", attempts.get(2).get("result"));
    assertNull(attempts.get(2).get("failureCause"));
    assertNotNull(attempts.get(2).get("latencyMillis"));
    for (final Map<String, Object> attempt : attempts) {
      assertEquals(body.get("correlationId"), attempt.get("correlationId"));
      assertEquals("AUTOMATIC", attempt.get("origin"));
    }
    final List<Instant> dates =
        attempts.stream().map(a -> Instant.parse(a.get("occurredOn").toString())).toList();
    assertEquals(dates.stream().sorted().toList(), dates);
    assertFalse(body.toString().contains(RAW_PROVIDER_TEXT));

    final List<String> lines = appender.list.stream().map(LogLines::render).toList();
    final List<String> dispatchLines =
        lines.stream()
            .filter(line -> line.contains("DISPATCH_ATTEMPT_RECORDED"))
            .filter(line -> line.contains("notificationId=" + id))
            .toList();
    assertEquals(3, dispatchLines.size());
    assertTrue(
        dispatchLines.stream().anyMatch(line -> line.contains("failureCause=TRANSPORT_ERROR")));
    assertTrue(
        dispatchLines.stream()
            .allMatch(line -> line.contains("correlationId=" + body.get("correlationId"))));
    assertFalse(lines.stream().anyMatch(line -> line.contains(RAW_PROVIDER_TEXT)));
  }

  @Test
  void theRecipientIsMaskedForOperatorCompleteForAdministratorAndClientIsForbidden() {
    final String id = deliveredNotification("tenant-1", "timeline-roles");

    final Object operator = timelineBody("tenant-1", Role.OPERADOR, id).get("recipient");
    final Object admin = timelineBody("tenant-1", Role.ADMINISTRADOR, id).get("recipient");

    assertEquals("alice@example.com", admin);
    assertNotNull(operator);
    assertFalse(operator.toString().contains("alice"));
    timeline("tenant-1", Role.CLIENTE, id).expectStatus().isForbidden();
  }

  @Test
  void aNotificationOfAnotherTenantAndAnUnknownIdAreBothNotFoundWithoutData() {
    final String id = deliveredNotification("tenant-1", "timeline-iso");

    final String other =
        timeline("tenant-2", Role.ADMINISTRADOR, id)
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    final String unknown =
        timeline("tenant-2", Role.ADMINISTRADOR, "00000000-0000-0000-0000-000000000000")
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertNotNull(other);
    assertFalse(other.contains("alice"));
    assertNotNull(unknown);
    timeline("tenant-1", Role.ADMINISTRADOR, id).expectStatus().isOk();
  }

  @Test
  void aNotificationWithTwentyAttemptsIsReturnedInUnderOneSecond() {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("timeline-twenty"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-twenty"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    notification.markQueued();
    final AttemptRecord record =
        new AttemptRecord(
            AttemptResult.RECOVERABLE_FAILURE,
            AttemptOrigin.AUTOMATIC,
            ProviderId.of("timeline-a"),
            false,
            15L,
            null,
            "corr-twenty",
            null);
    for (int i = 0; i < 19; i++) {
      notification.recordFailedOver(record);
    }
    notification.markDelivered(
        AttemptRecord.of(
            AttemptResult.ACCEPTED, AttemptOrigin.AUTOMATIC, ProviderId.of("simulated")));
    notificationRepository.save(notification).block();
    final String id = notification.notificationId().value();

    timelineBody("tenant-1", Role.OPERADOR, id);
    final Instant start = Instant.now();
    final Map<String, Object> body = timelineBody("tenant-1", Role.OPERADOR, id);
    final Duration elapsed = Duration.between(start, Instant.now());

    assertEquals(20, attempts(body).size());
    assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, "took " + elapsed);
  }
}
