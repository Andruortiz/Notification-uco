package co.edu.uco.notification.utils;

import java.util.regex.Pattern;

public record TraceParent(String value) {

  public static final String HEADER = "traceparent";
  public static final String CONTEXT_KEY = "traceparent";

  private static final Pattern VALID = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

  public TraceParent {
    Preconditions.requireNonBlank(value, "traceparent must not be blank");
    Preconditions.requireTrue(VALID.matcher(value).matches(), "traceparent has an invalid format");
  }

  public static TraceParent of(final String value) {
    return new TraceParent(value);
  }

  public static TraceParent fromOrNull(final String candidate) {
    if (candidate == null) {
      return null;
    }
    final String trimmed = candidate.trim();
    return VALID.matcher(trimmed).matches() ? new TraceParent(trimmed) : null;
  }
}
