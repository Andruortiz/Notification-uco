package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.port.in.RegisterRecipientBounceUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.ProviderMessageLookupPort;
import co.edu.uco.notification.core.repository.UndeliverableRecipientRepository;
import co.edu.uco.notification.core.usecase.RecipientBounceService;
import co.edu.uco.notification.core.usecase.RecipientGuardedSendNotificationService;
import co.edu.uco.notification.core.usecase.UndeliverableMarkService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class RecipientValidationConfig {

  @Bean
  @Primary
  SendNotificationUseCase recipientGuardedSendNotificationUseCase(
      @Qualifier("sendNotificationUseCase") final SendNotificationUseCase delegate,
      final UndeliverableRecipientRepository repository,
      final NotificationMetricsPort metricsPort) {
    return new RecipientGuardedSendNotificationService(delegate, repository, metricsPort);
  }

  @Bean
  RegisterRecipientBounceUseCase registerRecipientBounceUseCase(
      final ProviderMessageLookupPort lookupPort,
      final UndeliverableRecipientRepository repository,
      final NotificationMetricsPort metricsPort) {
    return new RecipientBounceService(lookupPort, repository, metricsPort, Clock.systemUTC());
  }

  @Bean
  UndeliverableMarkService undeliverableMarkService(
      final UndeliverableRecipientRepository repository) {
    return new UndeliverableMarkService(repository);
  }
}
