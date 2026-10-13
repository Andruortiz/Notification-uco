package co.edu.uco.notification.infrastructure.config;

import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.provider.brevo")
public record BrevoProviderProperties(
    String apiKey,
    String senderEmail,
    String senderName,
    String baseUrl,
    Long timeoutMs,
    Long connectTimeoutMs) {

  private static final String DEFAULT_BASE_URL = "https://api.brevo.com";
  private static final Long DEFAULT_TIMEOUT_MS = 10_000L;
  private static final Long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;

  public BrevoProviderProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    timeoutMs = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
    connectTimeoutMs = connectTimeoutMs == null ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
  }

  public Optional<String> disabledReason() {
    if (apiKey == null || apiKey.isBlank()) {
      return Optional.of("missing notification.provider.brevo.api-key (BREVO_API_KEY)");
    }
    if (senderEmail == null || senderEmail.isBlank()) {
      return Optional.of("missing notification.provider.brevo.sender-email (BREVO_SENDER_EMAIL)");
    }
    return Optional.empty();
  }
}
