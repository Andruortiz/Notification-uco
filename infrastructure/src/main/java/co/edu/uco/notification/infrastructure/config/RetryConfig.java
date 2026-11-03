package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.port.in.ResendNotificationUseCase;
import co.edu.uco.notification.core.port.in.RetryNotificationUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.usecase.ResendNotificationService;
import co.edu.uco.notification.core.usecase.RetryNotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryConfig {

  @Bean
  RetryNotificationUseCase retryNotificationUseCase(
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort) {
    return new RetryNotificationService(notificationRepository, eventPublisherPort);
  }

  @Bean
  ResendNotificationUseCase resendNotificationUseCase(
      final NotificationRepository notificationRepository,
      final SendNotificationUseCase sendNotificationUseCase) {
    return new ResendNotificationService(notificationRepository, sendNotificationUseCase);
  }
}
