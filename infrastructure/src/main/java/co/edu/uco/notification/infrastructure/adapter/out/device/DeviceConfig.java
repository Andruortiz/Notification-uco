package co.edu.uco.notification.infrastructure.adapter.out.device;

import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import co.edu.uco.notification.core.port.in.DispatchNotificationUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.DeviceLifecyclePort;
import co.edu.uco.notification.core.port.out.DeviceRegistryPort;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.core.usecase.DeviceService;
import co.edu.uco.notification.core.usecase.DispatchNotificationService;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.DeviceMongoAdapter;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.PushRecipientEncryptionSweep;
import co.edu.uco.notification.infrastructure.adapter.out.security.AesGcmTokenProtection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
class DeviceConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeviceConfig.class);

  @Bean
  AesGcmTokenProtection tokenProtection(
      final Environment environment,
      final ObjectProvider<MeterRegistry> meterRegistry,
      @Value("${DEVICE_TOKEN_KEY:}") final String tokenKey,
      @Value("${DEVICE_TOKEN_KEY_VERSION:1}") final int tokenKeyVersion,
      @Value("${DEVICE_FINGERPRINT_KEY:}") final String fingerprintKey,
      @Value("${DEVICE_FINGERPRINT_KEY_VERSION:1}") final int fingerprintKeyVersion) {
    return protection(
            environment.getActiveProfiles(),
            tokenKey,
            tokenKeyVersion,
            fingerprintKey,
            fingerprintKeyVersion)
        .withMeters(meterRegistry.getIfAvailable());
  }

  static boolean ephemeralKeysAllowed(final String[] activeProfiles) {
    return activeProfiles.length > 0
        && Arrays.stream(activeProfiles)
            .allMatch(profile -> "local".equals(profile) || "test".equals(profile));
  }

  static AesGcmTokenProtection protection(
      final String[] activeProfiles,
      final String tokenKey,
      final int tokenKeyVersion,
      final String fingerprintKey,
      final int fingerprintKeyVersion) {
    if (tokenKey.isBlank() || fingerprintKey.isBlank()) {
      if (!ephemeralKeysAllowed(activeProfiles)) {
        throw new IllegalStateException(
            "DEVICE_TOKEN_KEY and DEVICE_FINGERPRINT_KEY are required unless every active profile is"
                + " local or test");
      }
      LOGGER.warn("Device encryption keys not configured; using ephemeral keys for this process");
      return AesGcmTokenProtection.ephemeral();
    }
    return new AesGcmTokenProtection(
        AesGcmTokenProtection.decodeKey(tokenKey, "DEVICE_TOKEN_KEY"),
        tokenKeyVersion,
        AesGcmTokenProtection.decodeKey(fingerprintKey, "DEVICE_FINGERPRINT_KEY"),
        fingerprintKeyVersion);
  }

  @Bean
  DeviceService deviceService(final DeviceRegistryPort registry) {
    return new DeviceService(registry);
  }

  @Bean
  @Primary
  DispatchNotificationUseCase deviceAwareDispatchNotificationUseCase(
      final NotificationRepository notificationRepository,
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry,
      final NotificationEventPublisherPort eventPublisherPort,
      final RetryPolicy retryPolicy,
      final NotificationMetricsPort metricsPort,
      final DeviceLifecyclePort deviceLifecyclePort) {
    return new DispatchNotificationService(
        notificationRepository,
        channelCatalogPort,
        notificationSenderRegistry,
        eventPublisherPort,
        retryPolicy,
        metricsPort,
        deviceLifecyclePort);
  }

  @Bean
  ApplicationRunner deviceRetentionRunner(
      final DeviceMongoAdapter adapter,
      @Value("${notification.devices.retention-days:270}") final long retentionDays) {
    return arguments ->
        adapter
            .ensureRetentionIndex(Duration.ofDays(retentionDays))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                ignored -> {},
                error ->
                    LOGGER.error(
                        "Device retention index could not be ensured: {}",
                        error.getClass().getSimpleName()));
  }

  @Bean
  ApplicationRunner pushEncryptionSweepRunner(
      final ReactiveMongoTemplate mongoTemplate,
      final AesGcmTokenProtection protection,
      @Value("${notification.devices.sweep-enabled:true}") final boolean enabled,
      @Value("${notification.devices.sweep-batch-size:200}") final int batchSize) {
    return arguments -> {
      if (enabled) {
        new PushRecipientEncryptionSweep(mongoTemplate, protection)
            .run(batchSize)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                processed -> LOGGER.info("Push recipient encryption sweep finished: {}", processed),
                error ->
                    LOGGER.error(
                        "Push recipient encryption sweep failed: {}",
                        error.getClass().getSimpleName()));
      }
    };
  }
}
