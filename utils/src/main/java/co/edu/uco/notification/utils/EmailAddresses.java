package co.edu.uco.notification.utils;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailAddresses {

  private static final int MAX_LENGTH = 254;
  private static final Pattern PATTERN =
      Pattern.compile("^[^\\s@]{1,64}@[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})+$");
  private static final String MASK = "***";

  private EmailAddresses() {}

  public static String normalize(final String value) {
    return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  public static boolean isValid(final String value) {
    final String normalized = normalize(value);
    return normalized != null
        && normalized.length() <= MAX_LENGTH
        && PATTERN.matcher(normalized).matches();
  }

  public static String mask(final String value) {
    if (value == null) {
      return MASK;
    }
    final int at = value.lastIndexOf('@');
    if (at < 1) {
      return MASK;
    }
    return value.charAt(0) + MASK + value.substring(at);
  }
}
