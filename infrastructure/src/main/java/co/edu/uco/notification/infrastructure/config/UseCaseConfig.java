package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.port.in.*;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.NotificationUpdatesPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.usecase.*;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfig {

  @Bean
  RetryPolicy retryPolicy() {
    return new RetryPolicy();
  }

  @Bean
  SendNotificationUseCase sendNotificationUseCase(
      final ChannelCatalogPort channelCatalogPort,
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort) {
    return new SendNotificationService(
        channelCatalogPort, notificationRepository, eventPublisherPort);
  }

  @Bean
  NotificationSenderRegistry notificationSenderRegistry(
      final List<NotificationSenderPort> notificationSenderPorts) {
    return new NotificationSenderRegistry(notificationSenderPorts);
  }

  @Bean
  DispatchNotificationUseCase dispatchNotificationUseCase(
      final NotificationRepository notificationRepository,
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy) {
    return new DispatchNotificationService(
        notificationRepository,
        channelCatalogPort,
        notificationSenderRegistry,
        eventPublisherPort,
        retryPolicy);
  }

  @Bean
  GetNotificationStatusUseCase getNotificationStatusUseCase(
      final NotificationRepository notificationRepository) {
    return new GetNotificationStatusService(notificationRepository);
  }

  @Bean
  SearchNotificationsUseCase searchNotificationsUseCase(
      final NotificationRepository notificationRepository) {
    return new SearchNotificationsService(notificationRepository);
  }

  @Bean
  SendNotificationBatchUseCase sendNotificationBatchUseCase(
      final SendNotificationUseCase sendNotificationUseCase) {
    return new SendNotificationBatchService(sendNotificationUseCase);
  }

  @Bean
  SubscribeToNotificationUpdatesUseCase subscribeToNotificationUpdatesUseCase(
      final NotificationRepository notificationRepository,
      final NotificationUpdatesPort notificationUpdatesPort) {
    return new SubscribeToNotificationUpdatesService(
        notificationRepository, notificationUpdatesPort);
  }

  @Bean
  RequeuePendingNotificationsUseCase requeuePendingNotificationsUseCase(
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy,
      @Value("${notification.scheduler.pending-orphan-threshold-ms:60000}")
          final long pendingOrphanThresholdMs) {
    return new RequeuePendingNotificationsService(
        notificationRepository,
        eventPublisherPort,
        retryPolicy,
        Duration.ofMillis(pendingOrphanThresholdMs));
  }
}
