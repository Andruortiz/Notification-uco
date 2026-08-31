package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.NotificationStatus;
import co.edu.uco.notification.core.domain.Priority;
import co.edu.uco.notification.core.domain.ProviderId;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import co.edu.uco.notification.core.domain.TenantId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SendNotificationServiceTest {

  private final ChannelCatalogPort channelCatalogPort = Mockito.mock(ChannelCatalogPort.class);
  private final NotificationRepository notificationRepository =
      Mockito.mock(NotificationRepository.class);
  private final NotificationEventPublisherPort eventPublisherPort =
      Mockito.mock(NotificationEventPublisherPort.class);

  private SendNotificationService service;

  private static final TenantId TENANT_ID = TenantId.of("tenant-1");
  private static final ExternalId EXTERNAL_ID = ExternalId.of("order-42");
  private static final ChannelType CHANNEL_TYPE = ChannelType.of("EMAIL");

  @BeforeEach
  void setUp() {
    service =
        new SendNotificationService(channelCatalogPort, notificationRepository, eventPublisherPort);
  }

  private static SendNotificationCommand command() {
    return new SendNotificationCommand(
        TENANT_ID,
        EXTERNAL_ID,
        CHANNEL_TYPE,
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  private static ChannelRoute activeRoute() {
    return new ChannelRoute(CHANNEL_TYPE, List.of(ProviderId.of("brevo")), null);
  }

  @Test
  void sendAcceptsSavesPublishesAndEnqueuesWhenNoDuplicateExists() {
    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.empty());
    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(any(Notification.class))).thenReturn(Mono.empty());

    final SendNotificationResult result = service.send(command()).block();

    assertNotNull(result);
    assertEquals(NotificationStatus.PENDING, result.status());

    verify(notificationRepository).save(any(Notification.class));
    verify(eventPublisherPort).publish(any());
    verify(eventPublisherPort).enqueueForDispatch(any(Notification.class));
  }

  @Test
  void sendReturnsExistingNotificationWithoutSideEffectsWhenDuplicateExists() {
    final Notification existing =
        Notification.accept(
            TENANT_ID,
            EXTERNAL_ID,
            CHANNEL_TYPE,
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com"),
            NotificationContent.of("Body"),
            Priority.NORMAL);
    existing.pullEvents();

    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.just(existing));

    final SendNotificationResult result = service.send(command()).block();

    assertNotNull(result);
    assertEquals(existing.notificationId(), result.notificationId());

    verify(notificationRepository, never()).save(any(Notification.class));
    verify(eventPublisherPort, never()).publish(any());
    verify(eventPublisherPort, never()).enqueueForDispatch(any(Notification.class));
  }

  @Test
  void sendFailsWithChannelNotAvailableWhenNoActiveRoute() {
    when(channelCatalogPort.findActiveRoute(eq(CHANNEL_TYPE), eq(TENANT_ID)))
        .thenReturn(Mono.empty());

    StepVerifier.create(service.send(command()))
        .expectError(ChannelNotAvailableException.class)
        .verify();

    verify(notificationRepository, never())
        .findByTenantAndExternalId(any(TenantId.class), any(ExternalId.class));
  }

  @Test
  void sendRejectsNullCommand() {
    assertThrows(NullPointerException.class, () -> service.send(null));
  }

  @Test
  void constructorRejectsNullChannelCatalogPort() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationService(null, notificationRepository, eventPublisherPort));
  }

  @Test
  void constructorRejectsNullNotificationRepository() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationService(channelCatalogPort, null, eventPublisherPort));
  }

  @Test
  void constructorRejectsNullEventPublisherPort() {
    assertThrows(
        NullPointerException.class,
        () -> new SendNotificationService(channelCatalogPort, notificationRepository, null));
  }
}
