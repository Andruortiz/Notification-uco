package co.edu.uco.notification.utils;

import java.util.UUID;
import java.util.regex.Pattern;

public record CorrelationId(String value) {

  public static final String HEADER = "X-Correlation-Id";
  public static final String CONTEXT_KEY = "correlationId";
  public static final String AMQP_HEADER = "x-correlation-id";

  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public CorrelationId {
    Preconditions.requireNonBlank(value, "correlationId must not be blank");
    Preconditions.requireTrue(
        VALID.matcher(value).matches(), "correlationId has an invalid format");
  }

  public static CorrelationId of(final String value) {
    return new CorrelationId(value);
  }

  public static CorrelationId newId() {
    return new CorrelationId(UUID.randomUUID().toString());
  }

  public static CorrelationId fromOrNew(final String candidate) {
    return isValid(candidate) ? new CorrelationId(candidate.trim()) : newId();
  }

  public static CorrelationId fromOrNull(final String candidate) {
    return isValid(candidate) ? new CorrelationId(candidate.trim()) : null;
  }

  private static boolean isValid(final String candidate) {
    return candidate != null && VALID.matcher(candidate.trim()).matches();
  }
}
