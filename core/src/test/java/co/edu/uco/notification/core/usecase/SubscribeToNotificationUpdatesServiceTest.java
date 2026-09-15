package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.port.in.LiveUpdateAction;
import co.edu.uco.notification.core.port.in.SubscribeToNotificationUpdatesQuery;
import co.edu.uco.notification.core.port.out.NotificationUpdatesPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SubscribeToNotificationUpdatesServiceTest {

  private static final TenantId TENANT_ID = TenantId.of("tenant-1");

  private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
  private final NotificationUpdatesPort notificationUpdatesPort =
      mock(NotificationUpdatesPort.class);

  private SubscribeToNotificationUpdatesService service;

  @BeforeEach
  void setUp() {
    service =
        new SubscribeToNotificationUpdatesService(notificationRepository, notificationUpdatesPort);
  }

  private static Notification notification(final TenantId tenantId) {
    return Notification.accept(
        new NotificationRouting(
            tenantId,
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
  }

  private static SubscribeToNotificationUpdatesQuery aQuery() {
    return new SubscribeToNotificationUpdatesQuery(TENANT_ID, null, null, null, null, null);
  }

  @Test
  void subscribeEmitsTheInitialSnapshotAsUpsert() {
    final Notification notification = notification(TENANT_ID);
    when(notificationRepository.search(any())).thenReturn(Flux.just(notification));
    when(notificationUpdatesPort.updates()).thenReturn(Flux.never());

    StepVerifier.create(service.subscribe(aQuery()))
        .assertNext(
            update -> {
              assertEquals(LiveUpdateAction.UPSERT, update.action());
              assertEquals(notification.notificationId(), update.notification().notificationId());
            })
        .thenCancel()
        .verify();
  }

  @Test
  void subscribeEmitsUpsertWhenALiveUpdateStillMatches() {
    final Notification notification = notification(TENANT_ID);
    when(notificationRepository.search(any())).thenReturn(Flux.empty());
    when(notificationUpdatesPort.updates()).thenReturn(Flux.just(notification.notificationId()));
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    StepVerifier.create(service.subscribe(aQuery()))
        .assertNext(update -> assertEquals(LiveUpdateAction.UPSERT, update.action()))
        .verifyComplete();
  }

  @Test
  void subscribeEmitsRemoveWhenALiveUpdateNoLongerMatchesTheActiveFilter() {
    final Notification notification = notification(TENANT_ID);
    when(notificationRepository.search(any())).thenReturn(Flux.empty());
    when(notificationUpdatesPort.updates()).thenReturn(Flux.just(notification.notificationId()));
    when(notificationRepository.findById(notification.notificationId()))
        .thenReturn(Mono.just(notification));

    final SubscribeToNotificationUpdatesQuery filtered =
        new SubscribeToNotificationUpdatesQuery(
            TENANT_ID, null, null, NotificationStatus.FAILED, null, null);

    StepVerifier.create(service.subscribe(filtered))
        .assertNext(update -> assertEquals(LiveUpdateAction.REMOVE, update.action()))
        .verifyComplete();
  }

  @Test
  void subscribeEmitsRemoveWhenTheLiveUpdateBelongsToAnotherTenant() {
    final Notification otherTenantNotification = notification(TenantId.of("tenant-2"));
    when(notificationRepository.search(any())).thenReturn(Flux.empty());
    when(notificationUpdatesPort.updates())
        .thenReturn(Flux.just(otherTenantNotification.notificationId()));
    when(notificationRepository.findById(otherTenantNotification.notificationId()))
        .thenReturn(Mono.just(otherTenantNotification));

    StepVerifier.create(service.subscribe(aQuery()))
        .assertNext(update -> assertEquals(LiveUpdateAction.REMOVE, update.action()))
        .verifyComplete();
  }

  @Test
  void subscribeStartsWithAFreshSearchOnEveryInvocation() {
    when(notificationRepository.search(any())).thenReturn(Flux.empty());
    when(notificationUpdatesPort.updates()).thenReturn(Flux.empty());

    service.subscribe(aQuery()).blockLast();
    service.subscribe(aQuery()).blockLast();

    verify(notificationRepository, times(2)).search(any());
  }

  @Test
  void subscribeRejectsNullQuery() {
    assertThrows(NullPointerException.class, () -> service.subscribe(null));
  }

  @Test
  void constructorRejectsNullNotificationRepository() {
    assertThrows(
        NullPointerException.class,
        () -> new SubscribeToNotificationUpdatesService(null, notificationUpdatesPort));
  }

  @Test
  void constructorRejectsNullNotificationUpdatesPort() {
    assertThrows(
        NullPointerException.class,
        () -> new SubscribeToNotificationUpdatesService(notificationRepository, null));
  }
}
