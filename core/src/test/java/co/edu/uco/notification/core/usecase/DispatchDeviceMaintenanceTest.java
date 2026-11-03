package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.DeviceLifecyclePort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DispatchDeviceMaintenanceTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final String TOKEN = "device-token-0001";

  private final NotificationRepository repository = mock(NotificationRepository.class);
  private final ChannelCatalogPort catalog = mock(ChannelCatalogPort.class);
  private final NotificationSenderPort sender = mock(NotificationSenderPort.class);
  private final NotificationEventPublisherPort publisher =
      mock(NotificationEventPublisherPort.class);
  private final DeviceLifecyclePort lifecycle = mock(DeviceLifecyclePort.class);

  private DispatchNotificationService service;

  @BeforeEach
  void setUp() {
    when(sender.providerId()).thenReturn(ProviderId.of("fcm"));
    when(publisher.publish(any())).thenReturn(Mono.empty());
    when(lifecycle.invalidate(any(), any())).thenReturn(Mono.empty());
    when(lifecycle.touch(any(), any())).thenReturn(Mono.empty());
    service =
        new DispatchNotificationService(
            repository,
            catalog,
            new NotificationSenderRegistry(List.of(sender)),
            publisher,
            new RetryPolicy(),
            mock(NotificationMetricsPort.class),
            lifecycle);
  }

  private Notification stubbed(final String channel, final ProviderResult result) {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TENANT,
                ExternalId.of("order-42"),
                ChannelType.of(channel),
                RecipientId.of("recipient-1"),
                Recipient.of(TOKEN)),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    notification.markQueued();
    notification.pullEvents();
    when(repository.reserveForDispatch(notification.notificationId()))
        .thenReturn(Mono.just(notification));
    when(catalog.findActiveRoute(notification.channelType(), TENANT))
        .thenReturn(
            Mono.just(
                new ChannelRoute(ChannelType.of(channel), List.of(ProviderId.of("fcm")), null)));
    when(repository.save(notification)).thenReturn(Mono.just(notification));
    when(sender.send(notification)).thenReturn(Mono.just(result));
    return notification;
  }

  @Test
  void anInvalidRecipientFailsPermanentlyAndInvalidatesTheDevice() {
    final Notification notification =
        stubbed("PUSH", ProviderResult.of(AttemptResult.INVALID_RECIPIENT));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.FAILED, notification.status());
    final DeliveryAttempt attempt = notification.deliveryAttempts().getFirst();
    assertEquals(AttemptResult.INVALID_RECIPIENT, attempt.result());
    assertEquals(FailureCause.INVALID_RECIPIENT, attempt.failureCause());
    verify(lifecycle).invalidate(TENANT, TOKEN);
    verify(lifecycle, never()).touch(any(), any());
  }

  @Test
  void anAcceptedPushTouchesTheDevice() {
    final Notification notification = stubbed("PUSH", ProviderResult.of(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(lifecycle).touch(TENANT, TOKEN);
    verify(lifecycle, never()).invalidate(any(), any());
  }

  @Test
  void permanentAndRecoverableFailuresLeaveTheDeviceAlone() {
    final Notification permanent =
        stubbed("PUSH", ProviderResult.of(AttemptResult.PERMANENT_FAILURE));

    StepVerifier.create(service.dispatch(permanent.notificationId())).verifyComplete();

    final Notification recoverable =
        stubbed("PUSH", ProviderResult.of(AttemptResult.RECOVERABLE_FAILURE));

    StepVerifier.create(service.dispatch(recoverable.notificationId())).verifyComplete();

    verify(lifecycle, never()).invalidate(any(), any());
    verify(lifecycle, never()).touch(any(), any());
  }

  @Test
  void otherChannelsNeverReachTheDeviceLifecycle() {
    final Notification notification = stubbed("EMAIL", ProviderResult.of(AttemptResult.ACCEPTED));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    verify(lifecycle, never()).touch(any(), any());
  }

  @Test
  void aFailingCleanupDoesNotChangeTheDispatchOutcome() {
    when(lifecycle.invalidate(any(), any())).thenReturn(Mono.error(new IllegalStateException("x")));
    final Notification notification =
        stubbed("PUSH", ProviderResult.of(AttemptResult.INVALID_RECIPIENT));

    StepVerifier.create(service.dispatch(notification.notificationId())).verifyComplete();

    assertEquals(NotificationStatus.FAILED, notification.status());
  }
}
