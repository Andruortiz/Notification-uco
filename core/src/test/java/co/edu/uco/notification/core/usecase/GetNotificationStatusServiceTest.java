package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.AttemptRecord;
import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.GetNotificationStatusQuery;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetNotificationStatusServiceTest {

  private static final TenantId TENANT_ID = TenantId.of("tenant-1");

  private final NotificationRepository notificationRepository = mock(NotificationRepository.class);

  private GetNotificationStatusService service;

  @BeforeEach
  void setUp() {
    service = new GetNotificationStatusService(notificationRepository);
  }

  private static Notification acceptedNotification() {
    return Notification.accept(
        new NotificationRouting(
            TENANT_ID,
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
  }

  @Test
  void getStatusReturnsViewFromLastAttemptWhenAttemptsExist() {
    final Notification notification = acceptedNotification();
    notification.markQueued();
    notification.markDelivered(
        AttemptRecord.of(AttemptResult.ACCEPTED, AttemptOrigin.AUTOMATIC, ProviderId.of("brevo")));
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    final NotificationStatusView view =
        service
            .getStatus(new GetNotificationStatusQuery(TENANT_ID, notification.notificationId()))
            .block();

    assertNotNull(view);
    assertEquals(NotificationStatus.DELIVERED, view.status());
    assertEquals(ChannelType.of("EMAIL"), view.channelType());
    assertEquals(ProviderId.of("brevo"), view.lastProviderId());
    assertEquals(notification.deliveryAttempts().getFirst().occurredOn(), view.lastUpdatedAt());
  }

  @Test
  void getStatusReturnsAcceptedAtWhenNoAttemptsYet() {
    final Notification notification = acceptedNotification();
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    final NotificationStatusView view =
        service
            .getStatus(new GetNotificationStatusQuery(TENANT_ID, notification.notificationId()))
            .block();

    assertNotNull(view);
    assertEquals(NotificationStatus.PENDING, view.status());
    assertNull(view.lastProviderId());
    assertEquals(notification.acceptedAt(), view.lastUpdatedAt());
  }

  @Test
  void getStatusFailsWithNotFoundWhenMissing() {
    final NotificationId id = NotificationId.newId();
    when(notificationRepository.findById(id)).thenReturn(Mono.empty());

    StepVerifier.create(service.getStatus(new GetNotificationStatusQuery(TENANT_ID, id)))
        .expectError(NotificationNotFoundException.class)
        .verify();
  }

  @Test
  void getStatusFailsWithNotFoundWhenTenantDoesNotMatch() {
    final Notification notification = acceptedNotification();
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    final GetNotificationStatusQuery query =
        new GetNotificationStatusQuery(TenantId.of("tenant-2"), notification.notificationId());

    StepVerifier.create(service.getStatus(query))
        .expectError(NotificationNotFoundException.class)
        .verify();
  }

  @Test
  void getTimelineReturnsHeaderAndAttemptsInChronologicalOrder() {
    final Instant base = Instant.parse("2026-10-01T10:00:00Z");
    final Notification notification =
        Notification.reconstitute(
            NotificationId.newId(),
            new NotificationRouting(
                TENANT_ID,
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            NotificationStatus.DELIVERED,
            new NotificationMetadata(base, 1L),
            List.of(
                DeliveryAttempt.of(
                    base.plusSeconds(5),
                    AttemptResult.ACCEPTED,
                    AttemptOrigin.AUTOMATIC,
                    ProviderId.of("brevo")),
                DeliveryAttempt.of(
                    base,
                    AttemptResult.RECOVERABLE_FAILURE,
                    AttemptOrigin.AUTOMATIC,
                    ProviderId.of("brevo"))));
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(
            service.getTimeline(
                new GetNotificationStatusQuery(TENANT_ID, notification.notificationId())))
        .assertNext(
            view -> {
              assertEquals(NotificationStatus.DELIVERED, view.status());
              assertEquals("alice@example.com", view.recipient().address());
              assertEquals(AttemptResult.RECOVERABLE_FAILURE, view.attempts().get(0).result());
              assertEquals(AttemptResult.ACCEPTED, view.attempts().get(1).result());
            })
        .verifyComplete();
  }

  @Test
  void getTimelineReturnsEmptyAttemptsWhenThereAreNone() {
    final Notification notification = acceptedNotification();
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(
            service.getTimeline(
                new GetNotificationStatusQuery(TENANT_ID, notification.notificationId())))
        .assertNext(view -> assertTrue(view.attempts().isEmpty()))
        .verifyComplete();
  }

  @Test
  void getTimelineFailsWithNotFoundWhenTenantDoesNotMatch() {
    final Notification notification = acceptedNotification();
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(
            service.getTimeline(
                new GetNotificationStatusQuery(
                    TenantId.of("tenant-2"), notification.notificationId())))
        .expectError(NotificationNotFoundException.class)
        .verify();
  }

  @Test
  void getStatusRejectsNullQuery() {
    assertThrows(NullPointerException.class, () -> service.getStatus(null));
  }

  @Test
  void constructorRejectsNullNotificationRepository() {
    assertThrows(NullPointerException.class, () -> new GetNotificationStatusService(null));
  }

  @Test
  void getStatusExposesThePersistedCorrelationId() {
    final CorrelationId correlationId = CorrelationId.of("corr-status");
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TENANT_ID,
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            correlationId);
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(
            service.getStatus(
                new GetNotificationStatusQuery(TENANT_ID, notification.notificationId())))
        .assertNext(view -> assertEquals(correlationId, view.correlationId()))
        .verifyComplete();
  }

  @Test
  void getStatusLeavesTheCorrelationIdNullForLegacyNotifications() {
    final Notification notification = acceptedNotification();
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(
            service.getStatus(
                new GetNotificationStatusQuery(TENANT_ID, notification.notificationId())))
        .assertNext(view -> assertNull(view.correlationId()))
        .verifyComplete();
  }
}
