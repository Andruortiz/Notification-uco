package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.AttemptResult;
import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.NotificationStatus;
import co.edu.uco.notification.core.domain.Priority;
import co.edu.uco.notification.core.domain.ProviderId;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import co.edu.uco.notification.core.domain.TenantId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DispatchNotificationServiceTest {

  private final NotificationRepository notificationRepository =
      Mockito.mock(NotificationRepository.class);
  private final ChannelCatalogPort channelCatalogPort = Mockito.mock(ChannelCatalogPort.class);
  private final NotificationSenderPort notificationSenderPort =
      Mockito.mock(NotificationSenderPort.class);
  private final NotificationEventPublisherPort eventPublisherPort =
      Mockito.mock(NotificationEventPublisherPort.class);

  private DispatchNotificationService service;

  @BeforeEach
  void setUp() {
    service =
        new DispatchNotificationService(
            notificationRepository, channelCatalogPort, notificationSenderPort, eventPublisherPort);
  }

  private static Notification pendingNotification() {
    return Notification.accept(
        TenantId.of("tenant-1"),
        ExternalId.of("order-42"),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  private static ChannelRoute activeRoute() {
    return new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), null);
  }

  private void stubHappyPathUpTo(final Notification notification) {
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(channelCatalogPort.findActiveRoute(notification.channelType(), notification.tenantId()))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.save(notification)).thenReturn(Mono.just(notification));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
  }

  @Test
  void dispatchMarksDeliveredOnAcceptedOutcome() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.DELIVERED, notification.status());
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
    verify(notificationRepository).save(notification);
    verify(eventPublisherPort).publish(any());
  }

  @Test
  void dispatchMarksRecoverableOnRecoverableFailureOutcome() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.just(AttemptResult.RECOVERABLE_FAILURE));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
  }

  @Test
  void dispatchMarksFailedOnPermanentFailureOutcome() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.just(AttemptResult.PERMANENT_FAILURE));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.FAILED, notification.status());
  }

  @Test
  void dispatchFailsWithNotificationNotFoundWhenMissing() {
    final NotificationId id = NotificationId.newId();
    when(notificationRepository.findById(id)).thenReturn(Mono.empty());

    StepVerifier.create(service.dispatch(id))
        .expectError(NotificationNotFoundException.class)
        .verify();
  }

  @Test
  void dispatchFailsWithChannelNotAvailableAndNeverQueuesWhenNoRoute() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(channelCatalogPort.findActiveRoute(notification.channelType(), notification.tenantId()))
        .thenReturn(Mono.empty());

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ChannelNotAvailableException.class)
        .verify();

    assertEquals(NotificationStatus.PENDING, notification.status());
    verify(notificationSenderPort, never()).send(any(Notification.class));
    verify(notificationRepository, never()).save(any(Notification.class));
  }

  @Test
  void dispatchRejectsNullNotificationId() {
    assertThrows(NullPointerException.class, () -> service.dispatch(null));
  }

  @Test
  void constructorRejectsNullNotificationRepository() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                null, channelCatalogPort, notificationSenderPort, eventPublisherPort));
  }

  @Test
  void constructorRejectsNullChannelCatalogPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository, null, notificationSenderPort, eventPublisherPort));
  }

  @Test
  void constructorRejectsNullNotificationSenderPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository, channelCatalogPort, null, eventPublisherPort));
  }

  @Test
  void constructorRejectsNullEventPublisherPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository, channelCatalogPort, notificationSenderPort, null));
  }
}
