package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import co.edu.uco.notification.core.domain.event.DomainEvent;
import co.edu.uco.notification.core.domain.event.NotificationAccepted;
import co.edu.uco.notification.core.domain.event.NotificationDelivered;
import co.edu.uco.notification.core.domain.event.NotificationDiscarded;
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.event.NotificationQueued;
import co.edu.uco.notification.core.domain.event.NotificationRecoverable;
import co.edu.uco.notification.core.domain.event.NotificationRequeued;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.infrastructure.config.LogCapture;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.utils.CorrelationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.test.StepVerifier;

class NotificationRabbitPublisherLoggingTest {

  private static final RabbitTopologyProperties PROPERTIES =
      new RabbitTopologyProperties(
          new RabbitTopologyProperties.Dispatch(
              "notification.dispatch.exchange",
              "notification.dispatch",
              "notification.dispatch.queue"),
          new RabbitTopologyProperties.Dlq(
              "notification.dispatch.dlq.exchange",
              "notification.dispatch.dlq",
              "notification.dispatch.dlq.queue"),
          "notification.events.exchange");

  private static final NotificationId NOTIFICATION_ID = NotificationId.of("n-100");
  private static final TenantId TENANT_ID = TenantId.of("tenant-7");
  private static final CorrelationId CORRELATION_ID = CorrelationId.of("corr-100");

  private final NotificationRabbitPublisher publisher =
      new NotificationRabbitPublisher(
          mock(RabbitTemplate.class), new ObjectMapper().findAndRegisterModules(), PROPERTIES);

  private static List<DomainEvent> allEvents() {
    final Instant now = Instant.parse("2026-10-01T10:00:00Z");
    return List.of(
        new NotificationAccepted(NOTIFICATION_ID, TENANT_ID, CORRELATION_ID, now),
        new NotificationQueued(NOTIFICATION_ID, TENANT_ID, CORRELATION_ID, now),
        new NotificationDelivered(NOTIFICATION_ID, TENANT_ID, CORRELATION_ID, now),
        new NotificationRecoverable(NOTIFICATION_ID, TENANT_ID, CORRELATION_ID, now),
        new NotificationRequeued(NOTIFICATION_ID, TENANT_ID, CORRELATION_ID, now),
        new NotificationFailed(NOTIFICATION_ID, TENANT_ID, CORRELATION_ID, now),
        new NotificationDiscarded(NOTIFICATION_ID, TENANT_ID, CORRELATION_ID, now));
  }

  @Test
  void everyLifecycleEventIsLoggedWithTheThreeIdentifiersFromTheEventItself() {
    try (LogCapture capture = LogCapture.of(NotificationRabbitPublisher.class)) {
      StepVerifier.create(publisher.publish(allEvents())).verifyComplete();

      final List<ILoggingEvent> events = capture.events();
      assertEquals(7, events.size());
      events.forEach(
          event -> {
            assertEquals("corr-100", event.getMDCPropertyMap().get("correlationId"));
            assertEquals("tenant-7", event.getMDCPropertyMap().get("tenantId"));
            assertEquals("n-100", event.getMDCPropertyMap().get("notificationId"));
          });
    }
  }

  @Test
  void levelsFollowThePolicySuccessInfoRecoverableWarnPermanentError() {
    try (LogCapture capture = LogCapture.of(NotificationRabbitPublisher.class)) {
      StepVerifier.create(publisher.publish(allEvents())).verifyComplete();

      final List<Level> levels = capture.events().stream().map(ILoggingEvent::getLevel).toList();
      assertEquals(
          List.of(
              Level.INFO, Level.INFO, Level.INFO, Level.WARN, Level.INFO, Level.ERROR, Level.ERROR),
          levels);
    }
  }

  @Test
  void failureEventsCarryTheFailureCategoryAsAField() {
    try (LogCapture capture = LogCapture.of(NotificationRabbitPublisher.class)) {
      StepVerifier.create(publisher.publish(allEvents())).verifyComplete();

      final String rendered = capture.rendered();
      assertTrue(rendered.contains("failureCategory=RECOVERABLE_PROVIDER"));
      assertTrue(rendered.contains("failureCategory=PERMANENT_BUSINESS"));
    }
  }

  @Test
  void mdcIsRestoredAfterPublishing() {
    StepVerifier.create(publisher.publish(allEvents())).verifyComplete();

    assertEquals(null, org.slf4j.MDC.get("correlationId"));
  }
}
