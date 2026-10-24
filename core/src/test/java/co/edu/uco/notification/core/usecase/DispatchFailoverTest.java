package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.DeliveryAttempt;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.FailureCause;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AllProvidersCircuitOpenException;
import co.edu.uco.notification.core.exception.ProviderCircuitOpenException;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.exception.ProviderNotAvailableException;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DispatchFailoverTest {

  private static final ProviderId A = ProviderId.of("provider-a");
  private static final ProviderId B = ProviderId.of("provider-b");
  private static final ProviderId MISSING = ProviderId.of("provider-missing");

  private final NotificationRepository repository = Mockito.mock(NotificationRepository.class);
  private final ChannelCatalogPort catalog = Mockito.mock(ChannelCatalogPort.class);
  private final NotificationEventPublisherPort publisher =
      Mockito.mock(NotificationEventPublisherPort.class);
  private final NotificationMetricsPort metrics = Mockito.mock(NotificationMetricsPort.class);
  private final NotificationSenderPort senderA = Mockito.mock(NotificationSenderPort.class);
  private final NotificationSenderPort senderB = Mockito.mock(NotificationSenderPort.class);

  private DispatchNotificationService service;

  @BeforeEach
  void setUp() {
    when(senderA.providerId()).thenReturn(A);
    when(senderB.providerId()).thenReturn(B);
    when(senderA.supportsAttachments()).thenReturn(true);
    when(senderB.supportsAttachments()).thenReturn(true);
    service =
        new DispatchNotificationService(
            repository,
            catalog,
            new NotificationSenderRegistry(List.of(senderA, senderB)),
            publisher,
            new RetryPolicy(),
            metrics);
  }

  private static Notification pending() {
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
    notification.pullEvents();
    return notification;
  }

  private Notification stub(final ProviderId... route) {
    final Notification notification = pending();
    when(repository.reserveForDispatch(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(catalog.findActiveRoute(notification.channelType(), notification.tenantId()))
        .thenReturn(Mono.just(new ChannelRoute(ChannelType.of("EMAIL"), List.of(route), null)));
    when(repository.save(notification)).thenReturn(Mono.just(notification));
    when(publisher.publish(any())).thenReturn(Mono.empty());
    when(repository.releaseReservation(notification.notificationId())).thenReturn(Mono.empty());
    return notification;
  }

  private static Mono<ProviderResult> result(final AttemptResult result) {
    return Mono.just(ProviderResult.of(result));
  }

  @Test
  void recoverableFailureSwitchesToTheNextProviderAndRecordsBothAttempts() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(result(AttemptResult.RECOVERABLE_FAILURE));
    when(senderB.send(notification)).thenReturn(result(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.DELIVERED, notification.status());
    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    assertEquals(2, attempts.size());
    assertEquals(A, attempts.get(0).providerId());
    assertTrue(attempts.get(0).failedOver());
    assertEquals(AttemptResult.RECOVERABLE_FAILURE, attempts.get(0).result());
    assertEquals(B, attempts.get(1).providerId());
    assertFalse(attempts.get(1).failedOver());
    assertEquals(AttemptResult.ACCEPTED, attempts.get(1).result());
    verify(metrics)
        .dispatchAttempted(ChannelType.of("EMAIL"), A, AttemptResult.RECOVERABLE_FAILURE);
    verify(metrics).dispatchAttempted(ChannelType.of("EMAIL"), B, AttemptResult.ACCEPTED);
  }

  @Test
  void allProvidersRecoverableLeavesTheLastResultAndDoesNotSpendRetryBudget() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(result(AttemptResult.RECOVERABLE_FAILURE));
    when(senderB.send(notification)).thenReturn(result(AttemptResult.RECOVERABLE_FAILURE));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(2, notification.deliveryAttempts().size());
    assertEquals(B, notification.deliveryAttempts().get(1).providerId());
    assertFalse(notification.deliveryAttempts().get(1).failedOver());
    assertEquals(1, notification.recoverableAttemptsInCurrentCycle());
  }

  @Test
  void permanentFailureDoesNotSwitch() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(result(AttemptResult.PERMANENT_FAILURE));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.FAILED, notification.status());
    verify(senderB, never()).send(any());
    assertEquals(1, notification.deliveryAttempts().size());
  }

  @Test
  void acceptedDoesNotInvokeTheNextProvider() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(result(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(senderB, never()).send(any());
    assertEquals(1, notification.deliveryAttempts().size());
  }

  @Test
  void disabledProviderIsSkippedWithoutAttemptOrMetric() {
    final Notification notification = stub(A, B);
    when(senderA.disabledReason()).thenReturn(Optional.of("off"));
    when(senderB.send(notification)).thenReturn(result(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(senderA, never()).send(any());
    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(B, notification.deliveryAttempts().get(0).providerId());
    verify(metrics, never()).dispatchAttempted(any(), Mockito.eq(A), any(AttemptResult.class));
  }

  @Test
  void providerThatThrowsDisabledOnSendIsSkipped() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification))
        .thenReturn(Mono.error(new ProviderDisabledException(A, "no credentials")));
    when(senderB.send(notification)).thenReturn(result(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(NotificationStatus.DELIVERED, notification.status());
  }

  @Test
  void openCircuitIsSkippedWithoutAttempt() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(Mono.error(new ProviderCircuitOpenException(A)));
    when(senderB.send(notification)).thenReturn(result(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(B, notification.deliveryAttempts().get(0).providerId());
  }

  @Test
  void recoverableThenOpenCircuitKeepsTheRecoverableResultAsFinal() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(result(AttemptResult.RECOVERABLE_FAILURE));
    when(senderB.send(notification)).thenReturn(Mono.error(new ProviderCircuitOpenException(B)));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(1, notification.deliveryAttempts().size());
    assertFalse(notification.deliveryAttempts().get(0).failedOver());
  }

  @Test
  void allCircuitsOpenFailsWithoutAttemptsMetricOrStateChange() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(Mono.error(new ProviderCircuitOpenException(A)));
    when(senderB.send(notification)).thenReturn(Mono.error(new ProviderCircuitOpenException(B)));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(AllProvidersCircuitOpenException.class)
        .verify();

    assertEquals(NotificationStatus.IN_PROCESS, notification.status());
    assertTrue(notification.deliveryAttempts().isEmpty());
    verify(metrics, never()).dispatchAttempted(any(), any(), any(AttemptResult.class));
    verify(repository).releaseReservation(notification.notificationId());
    verify(repository, never()).save(any());
  }

  @Test
  void transportExceptionSwitchesAndRecordsTheFailingProvider() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(Mono.error(new IllegalStateException("down")));
    when(senderB.send(notification)).thenReturn(result(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    final List<DeliveryAttempt> attempts = notification.deliveryAttempts();
    assertEquals(2, attempts.size());
    assertEquals(A, attempts.get(0).providerId());
    assertTrue(attempts.get(0).failedOver());
    assertEquals(FailureCause.TRANSPORT_ERROR, attempts.get(0).failureCause());
    verify(metrics)
        .dispatchAttempted(ChannelType.of("EMAIL"), A, AttemptResult.RECOVERABLE_FAILURE);
  }

  @Test
  void transportExceptionInEveryProviderLeavesTheLastOneAsResult() {
    final Notification notification = stub(A, B);
    when(senderA.send(notification)).thenReturn(Mono.error(new IllegalStateException("a")));
    when(senderB.send(notification)).thenReturn(Mono.error(new IllegalStateException("b")));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.RECOVERABLE, notification.status());
    assertEquals(B, notification.deliveryAttempts().get(1).providerId());
  }

  @Test
  void providerWithoutAdapterIsSkippedWhenOthersAreInvocable() {
    final Notification notification = stub(MISSING, A);
    when(senderA.send(notification)).thenReturn(result(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(1, notification.deliveryAttempts().size());
    assertEquals(A, notification.deliveryAttempts().get(0).providerId());
  }

  @Test
  void routeWithoutAnyInvocableProviderFailsAsNotAvailable() {
    final Notification notification = stub(MISSING);

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ProviderNotAvailableException.class)
        .verify();

    assertTrue(notification.deliveryAttempts().isEmpty());
  }

  @Test
  void routeWhereEveryProviderIsDisabledFailsAsDisabled() {
    final Notification notification = stub(A, B);
    when(senderA.disabledReason()).thenReturn(Optional.of("off"));
    when(senderB.disabledReason()).thenReturn(Optional.of("off"));

    StepVerifier.create(service.dispatch(notification.notificationId()))
        .expectError(ProviderDisabledException.class)
        .verify();

    assertTrue(notification.deliveryAttempts().isEmpty());
  }
}
