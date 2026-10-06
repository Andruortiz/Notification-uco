package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.FixedConfiguration;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.port.in.*;
import co.edu.uco.notification.core.port.out.AttachmentScanRequestPort;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ContentTypeDetectorPort;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.core.port.out.MalwareScannerPort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.NotificationUpdatesPort;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import co.edu.uco.notification.core.port.out.ScanVerdictCachePort;
import co.edu.uco.notification.core.port.out.SubscriptionTicketPort;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.core.repository.NotificationBatchRepository;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.usecase.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  ExpireAbandonedUploadsUseCase expireAbandonedUploadsUseCase(
      final AttachmentUploadRepository attachmentUploadRepository,
      final AttachmentStoragePort attachmentStoragePort,
      final Clock clock,
      @Value("${notification.attachments.sweeper.scan-deadline:1h}") final Duration scanDeadline,
      @Value("${notification.attachments.sweeper.batch-size:100}") final int batchSize) {
    return new ExpireAbandonedUploadsService(
        attachmentUploadRepository, attachmentStoragePort, scanDeadline, batchSize, clock);
  }

  @Bean
  ApplyConfigurationChangeUseCase applyConfigurationChangeUseCase(
      final ConfigurationHolder configurationHolder,
      final ConfigurationValidator configurationValidator,
      final FixedConfiguration fixedConfiguration,
      final Clock clock) {
    return new ApplyConfigurationChangeService(
        configurationHolder, configurationValidator, fixedConfiguration, clock);
  }

  @Bean
  RestoreLastKnownConfigurationUseCase restoreLastKnownConfigurationUseCase(
      final LastKnownConfigurationPort lastKnownConfigurationPort,
      final ConfigurationHolder configurationHolder,
      final ConfigurationValidator configurationValidator,
      final FixedConfiguration fixedConfiguration,
      final ParametersProperties parametersProperties,
      final Clock clock) {
    return new RestoreLastKnownConfigurationService(
        lastKnownConfigurationPort,
        configurationHolder,
        configurationValidator,
        fixedConfiguration,
        clock,
        parametersProperties.lastKnownLoadTimeout());
  }

  @Bean
  SynchronizeConfigurationUseCase synchronizeConfigurationUseCase(
      final ParametersSourcePort parametersSourcePort,
      final ApplyConfigurationChangeUseCase applyConfigurationChangeUseCase,
      final LastKnownConfigurationPort lastKnownConfigurationPort,
      final ConfigurationHolder configurationHolder) {
    return new SynchronizeConfigurationService(
        parametersSourcePort,
        applyConfigurationChangeUseCase,
        lastKnownConfigurationPort,
        configurationHolder);
  }

  @Bean
  QueryConfigurationUseCase queryConfigurationUseCase(
      final ConfigurationHolder configurationHolder, final ParameterRegistry parameterRegistry) {
    return new QueryConfigurationService(configurationHolder, parameterRegistry);
  }

  @Bean
  RetryPolicy retryPolicy() {
    return new RetryPolicy();
  }

  @Bean
  AttachmentInspector attachmentInspector(
      final ContentTypeDetectorPort contentTypeDetectorPort,
      final MalwareScannerPort malwareScannerPort,
      final ScanVerdictCachePort scanVerdictCachePort) {
    return new AttachmentInspector(
        contentTypeDetectorPort, malwareScannerPort, scanVerdictCachePort);
  }

  @Bean
  AttachmentResolver attachmentResolver(
      final AttachmentInspector attachmentInspector,
      final AttachmentUploadRepository attachmentUploadRepository,
      final AttachmentStoragePort attachmentStoragePort) {
    return new AttachmentResolver(
        attachmentInspector, attachmentUploadRepository, attachmentStoragePort);
  }

  @Bean
  IssueAttachmentUploadUseCase issueAttachmentUploadUseCase(
      final AttachmentUploadRepository attachmentUploadRepository,
      final AttachmentStoragePort attachmentStoragePort,
      final AttachmentProperties attachmentProperties) {
    return new IssueAttachmentUploadService(
        attachmentUploadRepository,
        attachmentStoragePort,
        attachmentProperties.upload().expiration(),
        Clock.systemUTC());
  }

  @Bean
  CompleteAttachmentUploadUseCase completeAttachmentUploadUseCase(
      final AttachmentUploadRepository attachmentUploadRepository,
      final AttachmentStoragePort attachmentStoragePort,
      final AttachmentScanRequestPort attachmentScanRequestPort) {
    return new CompleteAttachmentUploadService(
        attachmentUploadRepository,
        attachmentStoragePort,
        attachmentScanRequestPort,
        Clock.systemUTC());
  }

  @Bean
  GetAttachmentUploadUseCase getAttachmentUploadUseCase(
      final AttachmentUploadRepository attachmentUploadRepository) {
    return new GetAttachmentUploadService(attachmentUploadRepository);
  }

  @Bean
  ScanAttachmentUploadUseCase scanAttachmentUploadUseCase(
      final AttachmentUploadRepository attachmentUploadRepository,
      final AttachmentStoragePort attachmentStoragePort,
      final AttachmentInspector attachmentInspector) {
    return new ScanAttachmentUploadService(
        attachmentUploadRepository, attachmentStoragePort, attachmentInspector, Clock.systemUTC());
  }

  @Bean
  SendNotificationUseCase sendNotificationUseCase(
      final ChannelCatalogPort channelCatalogPort,
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort,
      final AttachmentResolver attachmentResolver,
      final NotificationMetricsPort metricsPort) {
    return new SendNotificationService(
        channelCatalogPort,
        notificationRepository,
        eventPublisherPort,
        attachmentResolver,
        metricsPort);
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
      final RetryPolicy retryPolicy,
      final NotificationMetricsPort metricsPort) {
    return new DispatchNotificationService(
        notificationRepository,
        channelCatalogPort,
        notificationSenderRegistry,
        eventPublisherPort,
        retryPolicy,
        metricsPort);
  }

  @Bean
  QueryChannelCatalogUseCase queryChannelCatalogUseCase(
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry) {
    return new QueryChannelCatalogService(channelCatalogPort, notificationSenderRegistry);
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
      final SendNotificationUseCase sendNotificationUseCase,
      final NotificationBatchRepository notificationBatchRepository) {
    return new SendNotificationBatchService(sendNotificationUseCase, notificationBatchRepository);
  }

  @Bean
  SubscribeToNotificationUpdatesUseCase subscribeToNotificationUpdatesUseCase(
      final NotificationRepository notificationRepository,
      final NotificationUpdatesPort notificationUpdatesPort) {
    return new SubscribeToNotificationUpdatesService(
        notificationRepository, notificationUpdatesPort);
  }

  @Bean
  IssueSubscriptionTicketUseCase issueSubscriptionTicketUseCase(
      final SubscriptionTicketPort subscriptionTicketPort,
      final Clock clock,
      @Value("${notification.auth.subscription-ticket.ttl-seconds:30}") final long ttlSeconds) {
    return new IssueSubscriptionTicketService(
        subscriptionTicketPort, clock, Duration.ofSeconds(ttlSeconds));
  }

  @Bean
  RequeuePendingNotificationsUseCase requeuePendingNotificationsUseCase(
      final NotificationRepository notificationRepository,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy,
      @Value("${notification.scheduler.pending-orphan-threshold-ms:60000}")
          final long pendingOrphanThresholdMs,
      @Value("${notification.scheduler.in-process-timeout-ms:600000}")
          final long inProcessTimeoutMs,
      @Value("${notification.scheduler.batch-size:100}") final int batchSize,
      final NotificationMetricsPort metricsPort) {
    return new RequeuePendingNotificationsService(
        notificationRepository,
        eventPublisherPort,
        retryPolicy,
        Duration.ofMillis(pendingOrphanThresholdMs),
        Duration.ofMillis(inProcessTimeoutMs),
        batchSize,
        metricsPort);
  }
}
