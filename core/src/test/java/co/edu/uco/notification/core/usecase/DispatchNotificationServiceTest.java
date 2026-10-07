package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.event.NotificationFailed;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationMetadata;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.DispatchResultNotPersistedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.exception.ProviderNotAvailableException;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.utils.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DispatchNotificationServiceTest {

  private final NotificationMetricsPort metricsPort = Mockito.mock(NotificationMetricsPort.class);

  private final NotificationRepository notificationRepository =
      Mockito.mock(NotificationRepository.class);
  private final ChannelCatalogPort channelCatalogPort = Mockito.mock(ChannelCatalogPort.class);
  private final NotificationSenderPort notificationSenderPort =
      Mockito.mock(NotificationSenderPort.class);
  private final NotificationEventPublisherPort eventPublisherPort =
      Mockito.mock(NotificationEventPublisherPort.class);

  private DispatchNotificationService service;

  private NotificationSenderRegistry senderRegistry;

  private final RetryPolicy retryPolicy = new RetryPolicy();

  @BeforeEach
  void setUp() {
    when(notificationSenderPort.providerId()).thenReturn(ProviderId.of("brevo"));
    senderRegistry = new NotificationSenderRegistry(List.of(notificationSenderPort));
    service =
        new DispatchNotificationService(
            notificationRepository,
            channelCatalogPort,
            senderRegistry,
            eventPublisherPort,
            retryPolicy,
            metricsPort);
  }

  private static Notification pendingNotification() {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    notification.markQueued();
    return notification;
  }

  private static Notification notificationInStatus(final NotificationStatus status) {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        status,
        new NotificationMetadata(Instant.now(), 3L),
        List.of());
  }

  private void stubNotReserved(final Notification notification) {
    when(notificationRepository.reserveForDispatch(notification.notificationId()))
        .thenReturn(Mono.empty());
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));
  }

  private static Notification pendingNotificationWithRecoverableAttempts(final int count) {
    final List<AttemptResult> results = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      results.add(AttemptResult.RECOVERABLE_FAILURE);
    }
    return pendingNotificationWithAttempts(results);
  }

  private static Notification pendingNotificationWithAttempts(final List<AttemptResult> results) {
    final NotificationId id = NotificationId.newId();
    final Instant now = Instant.now();
    final List<DeliveryAttempt> attempts = new ArrayList<>();
    for (final AttemptResult result : results) {
      attempts.add(
          DeliveryAttempt.of(now, result, AttemptOrigin.AUTOMATIC, ProviderId.of("brevo")));
    }
    return Notification.reconstitute(
        id,
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
        NotificationStatus.IN_PROCESS,
        new NotificationMetadata(now, 1L),
        attempts);
  }

  private static ChannelRoute activeRoute() {
    return new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), null);
  }

  private void stubHappyPathUpTo(final Notification notification) {
    when(notificationRepository.reserveForDispatch(notification.notificationId()))
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
  void dispatchMarksFailedWhenRecoverableRetriesAreExhausted() {
    final Notification notification = pendingNotificationWithRecoverableAttempts(4);
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.just(AttemptResult.RECOVERABLE_FAILURE));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.FAILED, notification.status());
    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    assertEquals(AttemptResult.RECOVERABLE_FAILURE, attempts.get(attempts.size() - 1).result());
  }

  @Test
  void dispatchCountsOnlyRecoverableAttemptsAmongMixedHistory() {
    final Notification notification =
        pendingNotificationWithAttempts(
            List.of(
                AttemptResult.PERMANENT_FAILURE,
                AttemptResult.RECOVERABLE_FAILURE,
                AttemptResult.RECOVERABLE_FAILURE,
                AttemptResult.RECOVERABLE_FAILURE));
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
    when(notificationRepository.reserveForDispatch(id)).thenReturn(Mono.empty());
    when(notificationRepository.findById(id)).thenReturn(Mono.empty());

    StepVerifier.create(service.dispatch(id))
        .expectError(NotificationNotFoundException.class)
        .verify();

    verify(notificationSenderPort, never()).send(any(Notification.class));
  }

  @Test
  void dispatchFailsWithChannelNotAvailableAndNeverQueuesWhenNoRoute() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    when(notificationRepository.reserveForDispatch(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(channelCatalogPort.findActiveRoute(notification.channelType(), notification.tenantId()))
        .thenReturn(Mono.empty());
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ChannelNotAvailableException.class)
        .verify();

    verify(notificationRepository).releaseReservation(notification.notificationId());
    verify(notificationSenderPort, never()).send(any(Notification.class));
    verify(notificationRepository, never()).save(any(Notification.class));
  }

  @Test
  void dispatchSendsThroughTheAdapterOfThePreferredProviderOnly() {
    final NotificationSenderPort otherSender = Mockito.mock(NotificationSenderPort.class);
    when(otherSender.providerId()).thenReturn(ProviderId.of("otro"));
    final DispatchNotificationService serviceWithTwoSenders =
        new DispatchNotificationService(
            notificationRepository,
            channelCatalogPort,
            new NotificationSenderRegistry(List.of(otherSender, notificationSenderPort)),
            eventPublisherPort,
            retryPolicy,
            metricsPort);
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(serviceWithTwoSenders.dispatch(notification.notificationId()))
        .verifyComplete();

    verify(notificationSenderPort).send(notification);
    verify(otherSender, never()).send(any(Notification.class));
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
  }

  @Test
  void
      dispatchFailsWithProviderNotAvailableAndLeavesTheNotificationUntouchedWhenNoAdapterMatches() {
    final NotificationSenderPort otherSender = Mockito.mock(NotificationSenderPort.class);
    when(otherSender.providerId()).thenReturn(ProviderId.of("otro"));
    final DispatchNotificationService serviceWithoutThePreferredProvider =
        new DispatchNotificationService(
            notificationRepository,
            channelCatalogPort,
            new NotificationSenderRegistry(List.of(otherSender)),
            eventPublisherPort,
            retryPolicy,
            metricsPort);
    final Notification notification = pendingNotification();
    notification.pullEvents();
    when(notificationRepository.reserveForDispatch(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(channelCatalogPort.findActiveRoute(notification.channelType(), notification.tenantId()))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(serviceWithoutThePreferredProvider.dispatch(notification.notificationId()))
        .expectError(ProviderNotAvailableException.class)
        .verify();

    verify(notificationRepository).releaseReservation(notification.notificationId());
    assertTrue(notification.deliveryAttempts().isEmpty());
    verify(otherSender, never()).send(any(Notification.class));
    verify(notificationRepository, never()).save(any(Notification.class));
    verify(eventPublisherPort, never()).publish(any());
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
                null,
                channelCatalogPort,
                senderRegistry,
                eventPublisherPort,
                retryPolicy,
                metricsPort));
  }

  @Test
  void constructorRejectsNullChannelCatalogPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository,
                null,
                senderRegistry,
                eventPublisherPort,
                retryPolicy,
                metricsPort));
  }

  @Test
  void constructorRejectsNullNotificationSenderRegistry() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository,
                channelCatalogPort,
                null,
                eventPublisherPort,
                retryPolicy,
                metricsPort));
  }

  @Test
  void constructorRejectsNullEventPublisherPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository,
                channelCatalogPort,
                senderRegistry,
                null,
                retryPolicy,
                metricsPort));
  }

  @Test
  void constructorRejectsNullRetryPolicy() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository,
                channelCatalogPort,
                senderRegistry,
                eventPublisherPort,
                null,
                metricsPort));
  }

  private static Notification pendingNotificationWithAttachment() {
    final byte[] bytes = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
    final Notification notification = withAttachment(bytes);
    notification.markQueued();
    return notification;
  }

  private static Notification withAttachment(final byte[] bytes) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-attachment"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(
            NotificationContent.of(
                "Subject",
                "Body",
                List.of(
                    new Attachment(
                        TenantId.of("tenant-1"),
                        "invoice.pdf",
                        "application/pdf",
                        bytes.length,
                        Sha256Digest.of(bytes),
                        new AttachmentSource.EmbeddedContent(bytes)))),
            Priority.NORMAL));
  }

  @Test
  void aNotificationWithAttachmentsFailsWithoutCallingAProviderThatCannotSendThem() {
    final Notification notification = pendingNotificationWithAttachment();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.supportsAttachments()).thenReturn(false);

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(notificationSenderPort, never()).send(any());
    assertEquals(NotificationStatus.FAILED, notification.status());
    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(AttemptResult.PERMANENT_FAILURE, notification.deliveryAttempts().get(0).result());
    assertEquals(ProviderId.of("brevo"), notification.deliveryAttempts().get(0).providerId());
    verify(notificationRepository).save(notification);
    verify(eventPublisherPort)
        .publish(
            Mockito.argThat(
                events -> events.stream().anyMatch(event -> event instanceof NotificationFailed)));
  }

  @Test
  void theSameProviderStillSendsNotificationsWithoutAttachments() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.supportsAttachments()).thenReturn(false);
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(notificationSenderPort).send(notification);
    assertEquals(NotificationStatus.DELIVERED, notification.status());
  }

  @ParameterizedTest
  @EnumSource(
      value = NotificationStatus.class,
      names = {"IN_PROCESS", "RECOVERABLE", "DELIVERED", "FAILED", "DISCARDED"})
  void aRedeliveryOnAnyStatusOtherThanPendingIsIgnoredWithoutCallingTheProvider(
      final NotificationStatus status) {
    final Notification notification = notificationInStatus(status);
    stubNotReserved(notification);

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(notificationSenderPort, never()).send(any(Notification.class));
    verify(notificationRepository, never()).save(any(Notification.class));
    verify(notificationRepository, never()).releaseReservation(any());
    verify(eventPublisherPort, never()).publish(any());
    assertEquals(status, notification.status());
  }

  @Test
  void whenTheReservationIsWonTheProviderIsCalledExactlyOnce() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(notificationSenderPort, times(1)).send(notification);
    verify(notificationRepository, never()).findById(any());
  }

  @Test
  void aHundredRedeliveriesOfTheSameMessageProduceASingleSend() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    when(notificationRepository.reserveForDispatch(notification.notificationId()))
        .thenReturn(Mono.just(notification), Mono.empty());
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(channelCatalogPort.findActiveRoute(notification.channelType(), notification.tenantId()))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.save(notification)).thenReturn(Mono.just(notification));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(
            Flux.range(0, 100).concatMap(i -> service.dispatch(notification.notificationId())))
        .verifyComplete();

    verify(notificationSenderPort, times(1)).send(notification);
    verify(notificationRepository, times(1)).save(notification);
  }

  @Test
  void theResultIsSavedWithTheInstanceReturnedByTheReservation() {
    final Notification reserved = pendingNotification();
    reserved.pullEvents();
    stubHappyPathUpTo(reserved);
    when(notificationSenderPort.send(reserved)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(reserved.notificationId())).verifyComplete();

    final ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(saved.capture());
    assertSame(reserved, saved.getValue());
  }

  @Test
  void eventsArePublishedOnlyAfterTheResultIsPersisted() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    final InOrder inOrder = Mockito.inOrder(notificationRepository, eventPublisherPort);
    inOrder.verify(notificationRepository).save(notification);
    inOrder.verify(eventPublisherPort).publish(any());
  }

  @Test
  void aFailedSaveAfterAnAcceptedSendIsRetriedWithoutSendingAgain() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.save(notification))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")))
        .thenReturn(Mono.just(notification));
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(notificationSenderPort, times(1)).send(notification);
    verify(notificationRepository, times(2)).save(notification);
    verify(notificationRepository, never()).releaseReservation(any());
    assertEquals(NotificationStatus.DELIVERED, notification.status());
  }

  @Test
  void aSaveThatKeepsFailingIsBoundedAndNeverSendsASecondTimeNorReleasesTheReservation() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.save(notification))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectErrorSatisfies(
            error -> {
              assertInstanceOf(DispatchResultNotPersistedException.class, error);
              assertInstanceOf(IllegalStateException.class, error.getCause());
            })
        .verify();

    verify(notificationSenderPort, times(1)).send(notification);
    verify(notificationRepository, times(3)).save(notification);
    verify(notificationRepository, never()).releaseReservation(any());
    verify(eventPublisherPort, never()).publish(any());
  }

  @Test
  void aVersionConflictOnSaveIsNotRetried() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.save(notification))
        .thenReturn(
            Mono.error(new NotificationVersionConflictException(notification.notificationId())));
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(NotificationVersionConflictException.class)
        .verify();

    verify(notificationRepository, times(1)).save(notification);
  }

  @Test
  void aProviderThatThrowsBeforeProducingAResultReleasesTheReservationAndPropagatesTheError() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.error(new ProviderDisabledException(ProviderId.of("brevo"), "disabled")));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ProviderDisabledException.class)
        .verify();

    verify(notificationRepository).releaseReservation(notification.notificationId());
    verify(notificationRepository, never()).save(any(Notification.class));
    verify(eventPublisherPort, never()).publish(any());
  }

  @Test
  void aFailureReleasingTheReservationDoesNotHideTheOriginalError() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.error(new ProviderDisabledException(ProviderId.of("brevo"), "disabled")));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ProviderDisabledException.class)
        .verify();
  }

  @Test
  void aRecoverableFailureIsCountedOnlyWithinTheCurrentCycle() {
    final Instant now = Instant.now();
    final List<DeliveryAttempt> previousCycle = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      previousCycle.add(
          DeliveryAttempt.of(
              now,
              AttemptResult.RECOVERABLE_FAILURE,
              AttemptOrigin.AUTOMATIC,
              ProviderId.of("brevo"),
              1));
    }
    final Notification notification =
        Notification.reconstitute(
            NotificationId.newId(),
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL),
            NotificationStatus.IN_PROCESS,
            new NotificationMetadata(now, 1L, now, now, 2),
            previousCycle);
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.just(AttemptResult.RECOVERABLE_FAILURE));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(2, notification.deliveryAttempts().getLast().cycle());
  }

  @Test
  void aProviderThatSupportsAttachmentsReceivesThem() {
    final Notification notification = pendingNotificationWithAttachment();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.supportsAttachments()).thenReturn(true);
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(notificationSenderPort).send(notification);
    assertEquals(NotificationStatus.DELIVERED, notification.status());
  }

  @Test
  void aFailedReservationReleaseIsReportedWithItsErrorCodeAndNoCategoryText() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.error(new ProviderDisabledException(ProviderId.of("brevo"), "disabled")));

    try (LogCapture logs = new LogCapture(DispatchNotificationService.class)) {
      StepVerifier.create(service.dispatch(notification.notificationId()))
          .expectError(ProviderDisabledException.class)
          .verify();

      assertEquals(1, logs.records(Level.SEVERE, "DISPATCH_RESERVATION_NOT_RELEASED").size());
      final String message = logs.records().get(0).getMessage();
      assertTrue(
          message.contains("errorCode=" + ErrorCode.DISPATCH_RESERVATION_NOT_RELEASED.format()));
      assertFalse(message.contains("category="));
    }
  }

  @Test
  void eventsThatCouldNotBePublishedAreReportedWithTheirErrorCodeAndNoCategoryText() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(eventPublisherPort.publish(any()))
        .thenReturn(Mono.error(new IllegalStateException("broker down")));
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    try (LogCapture logs = new LogCapture(DispatchNotificationService.class)) {
      StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

      assertEquals(1, logs.records(Level.SEVERE, "DISPATCH_EVENTS_NOT_PUBLISHED").size());
      final String message = logs.records().get(0).getMessage();
      assertTrue(message.contains("errorCode=" + ErrorCode.DISPATCH_EVENTS_NOT_PUBLISHED.format()));
      assertFalse(message.contains("category="));
    }
  }

  @Test
  void anAcceptedAttemptCountsOnceAndMeasuresTheProviderCall() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(metricsPort)
        .dispatchAttempted(ChannelType.of("EMAIL"), ProviderId.of("brevo"), AttemptResult.ACCEPTED);
    final ArgumentCaptor<java.time.Duration> duration =
        ArgumentCaptor.forClass(java.time.Duration.class);
    verify(metricsPort)
        .providerCalled(
            Mockito.eq(ProviderId.of("brevo")),
            Mockito.eq(AttemptResult.ACCEPTED),
            duration.capture());
    assertFalse(duration.getValue().isNegative());
    verify(metricsPort, never()).errorRecorded(any());
    verify(metricsPort, never()).errorRecorded(any(), any(), any());
  }

  @Test
  void aRecoverableAttemptIsCountedWithItsResultAndDuration() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.just(AttemptResult.RECOVERABLE_FAILURE));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(metricsPort)
        .dispatchAttempted(
            ChannelType.of("EMAIL"), ProviderId.of("brevo"), AttemptResult.RECOVERABLE_FAILURE);
    verify(metricsPort)
        .providerCalled(
            Mockito.eq(ProviderId.of("brevo")),
            Mockito.eq(AttemptResult.RECOVERABLE_FAILURE),
            any(java.time.Duration.class));
  }

  @Test
  void aRejectionBeforeCallingTheProviderCountsFailedWithoutDuration() {
    final Notification notification = pendingNotificationWithAttachment();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationSenderPort.supportsAttachments()).thenReturn(false);

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(metricsPort)
        .dispatchAttempted(
            ChannelType.of("EMAIL"), ProviderId.of("brevo"), AttemptResult.PERMANENT_FAILURE);
    verify(metricsPort, never()).providerCalled(any(), any(), any());
  }

  @Test
  void aDisabledProviderCountsFailedWithoutDuration() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.error(new ProviderDisabledException(ProviderId.of("brevo"), "disabled")));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ProviderDisabledException.class)
        .verify();

    verify(metricsPort)
        .dispatchAttempted(
            ChannelType.of("EMAIL"), ProviderId.of("brevo"), AttemptResult.PERMANENT_FAILURE);
    verify(metricsPort, never()).providerCalled(any(), any(), any());
  }

  @Test
  void aRedeliveryThatIsNotReservedCountsNoAttempt() {
    final Notification notification = notificationInStatus(NotificationStatus.DELIVERED);
    stubNotReserved(notification);

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    Mockito.verifyNoInteractions(metricsPort);
  }

  @Test
  void aChannelWithoutAnActiveRouteCountsNoAttempt() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    when(notificationRepository.reserveForDispatch(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(channelCatalogPort.findActiveRoute(notification.channelType(), notification.tenantId()))
        .thenReturn(Mono.empty());
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ChannelNotAvailableException.class)
        .verify();

    Mockito.verifyNoInteractions(metricsPort);
  }

  @Test
  void eventsThatCouldNotBePublishedCountAnErrorWithChannelAndProvider() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(eventPublisherPort.publish(any()))
        .thenReturn(Mono.error(new IllegalStateException("broker down")));
    when(notificationSenderPort.send(notification)).thenReturn(Mono.just(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(metricsPort)
        .errorRecorded(
            ErrorCode.DISPATCH_EVENTS_NOT_PUBLISHED,
            ChannelType.of("EMAIL"),
            ProviderId.of("brevo"));
  }

  @Test
  void aFailedReservationReleaseCountsAnErrorWithTheChannelOnly() {
    final Notification notification = pendingNotification();
    notification.pullEvents();
    stubHappyPathUpTo(notification);
    when(notificationRepository.releaseReservation(notification.notificationId()))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));
    when(notificationSenderPort.send(notification))
        .thenReturn(Mono.error(new ProviderDisabledException(ProviderId.of("brevo"), "disabled")));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ProviderDisabledException.class)
        .verify();

    verify(metricsPort)
        .errorRecorded(ErrorCode.DISPATCH_RESERVATION_NOT_RELEASED, ChannelType.of("EMAIL"), null);
  }

  @Test
  void constructorRejectsNullMetricsPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new DispatchNotificationService(
                notificationRepository,
                channelCatalogPort,
                senderRegistry,
                eventPublisherPort,
                retryPolicy,
                null));
  }
}
