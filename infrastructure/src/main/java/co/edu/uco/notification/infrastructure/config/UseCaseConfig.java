package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationBatchUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.usecase.DispatchNotificationService;
import co.edu.uco.notification.core.usecase.GetNotificationStatusService;
import co.edu.uco.notification.core.usecase.SendNotificationBatchService;
import co.edu.uco.notification.core.usecase.SendNotificationService;
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
  DispatchNotificationUseCase dispatchNotificationUseCase(
      final NotificationRepository notificationRepository,
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderPort notificationSenderPort,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy) {
    return new DispatchNotificationService(
        notificationRepository,
        channelCatalogPort,
        notificationSenderPort,
        eventPublisherPort,
        retryPolicy);
  }

  @Bean
  GetNotificationStatusUseCase getNotificationStatusUseCase(
      final NotificationRepository notificationRepository) {
    return new GetNotificationStatusService(notificationRepository);
  }

  @Bean
  SendNotificationBatchUseCase sendNotificationBatchUseCase(
      final SendNotificationUseCase sendNotificationUseCase) {
    return new SendNotificationBatchService(sendNotificationUseCase);
  }
}
