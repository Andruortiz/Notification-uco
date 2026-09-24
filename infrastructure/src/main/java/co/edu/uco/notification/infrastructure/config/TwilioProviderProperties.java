package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.PhoneNumbers;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.provider.twilio")
public record TwilioProviderProperties(
    String accountSid,
    String authToken,
    String fromNumber,
    String baseUrl,
    Long timeoutMs,
    Long connectTimeoutMs) {

  private static final String DEFAULT_BASE_URL = "https://api.twilio.com";
  private static final Long DEFAULT_TIMEOUT_MS = 10_000L;
  private static final Long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;
  private static final Pattern ACCOUNT_SID = Pattern.compile("^AC[0-9a-fA-F]{32}$");

  public TwilioProviderProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    timeoutMs = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
    connectTimeoutMs = connectTimeoutMs == null ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
  }

  public Optional<String> disabledReason() {
    if (isBlank(accountSid)) {
      return Optional.of("missing notification.provider.twilio.account-sid (TWILIO_ACCOUNT_SID)");
    }
    if (isBlank(authToken)) {
      return Optional.of("missing notification.provider.twilio.auth-token (TWILIO_AUTH_TOKEN)");
    }
    if (isBlank(fromNumber)) {
      return Optional.of("missing notification.provider.twilio.from-number (TWILIO_FROM_NUMBER)");
    }
    if (!ACCOUNT_SID.matcher(accountSid).matches()) {
      return Optional.of(
          "malformed notification.provider.twilio.account-sid (TWILIO_ACCOUNT_SID): "
              + "expected AC followed by 32 hexadecimal characters");
    }
    if (!PhoneNumbers.isE164(fromNumber)) {
      return Optional.of(
          "malformed notification.provider.twilio.from-number (TWILIO_FROM_NUMBER): "
              + "expected international format +<country code><number>");
    }
    return Optional.empty();
  }

  @Override
  public String toString() {
    return "TwilioProviderProperties[baseUrl="
        + baseUrl
        + ", timeoutMs="
        + timeoutMs
        + ", connectTimeoutMs="
        + connectTimeoutMs
        + "]";
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
