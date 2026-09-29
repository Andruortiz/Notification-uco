package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.infrastructure.adapter.out.antivirus.ClamAvMalwareScannerAdapter;
import co.edu.uco.notification.infrastructure.adapter.out.storage.MinioAttachmentStorageAdapter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AttachmentHealthConfig {

  @Bean
  ReactiveHealthIndicator clamavHealthIndicator(final ClamAvMalwareScannerAdapter scanner) {
    return () -> scanner.ping().map(AttachmentHealthConfig::toHealth);
  }

  @Bean
  ReactiveHealthIndicator attachmentStorageHealthIndicator(
      final MinioAttachmentStorageAdapter storage) {
    return () -> storage.ping().map(AttachmentHealthConfig::toHealth);
  }

  private static Health toHealth(final boolean reachable) {
    return reachable ? Health.up().build() : Health.down().build();
  }
}
