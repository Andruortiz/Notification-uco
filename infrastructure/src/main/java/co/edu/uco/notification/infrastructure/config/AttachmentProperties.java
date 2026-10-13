package co.edu.uco.notification.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.attachments")
public record AttachmentProperties(Scan scan, Upload upload, Storage storage, ClamAv clamav) {

  public record Scan(
      Duration timeout, Duration cleanVerdictTtl, int consumerConcurrency, int maxAttempts) {}

  public record Upload(Duration expiration) {}

  public record Storage(
      String bucket, String endpoint, String publicEndpoint, String accessKey, String secretKey) {

    @Override
    public String toString() {
      return "Storage[bucket="
          + bucket
          + ", endpoint="
          + endpoint
          + ", publicEndpoint="
          + publicEndpoint
          + "]";
    }
  }

  public record ClamAv(String host, int port, int maxConcurrentScans) {}
}
