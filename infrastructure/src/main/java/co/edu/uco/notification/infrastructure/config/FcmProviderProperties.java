package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.provider.fcm")
public record FcmProviderProperties(
    String credentialsJson,
    String credentialsFile,
    String baseUrl,
    String tokenUrl,
    Long timeoutMs,
    Long connectTimeoutMs) {

  private static final String DEFAULT_BASE_URL = "https://fcm.googleapis.com";
  private static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
  private static final Long DEFAULT_TIMEOUT_MS = 10_000L;
  private static final Long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;

  public FcmProviderProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? DEFAULT_TOKEN_URL : tokenUrl;
    timeoutMs = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
    connectTimeoutMs = connectTimeoutMs == null ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
  }

  @Override
  public String toString() {
    return "FcmProviderProperties[baseUrl="
        + baseUrl
        + ", tokenUrl="
        + tokenUrl
        + ", timeoutMs="
        + timeoutMs
        + ", connectTimeoutMs="
        + connectTimeoutMs
        + "]";
  }
}
