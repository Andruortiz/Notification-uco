package co.edu.uco.notification.utils;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailAddresses {

  private static final int MAX_LENGTH = 254;
  private static final Pattern LOCAL_PART = Pattern.compile("[^\\s@]{1,64}");
  private static final Pattern DOMAIN_LABEL = Pattern.compile("[A-Za-z0-9-]{1,63}");
  private static final String MASK = "***";

  private EmailAddresses() {}

  public static String normalize(final String value) {
    return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  public static boolean isValid(final String value) {
    final String normalized = normalize(value);
    return normalized != null && normalized.length() <= MAX_LENGTH && hasValidShape(normalized);
  }

  private static boolean hasValidShape(final String normalized) {
    final int at = normalized.indexOf('@');
    if (at < 1
        || at != normalized.lastIndexOf('@')
        || !LOCAL_PART.matcher(normalized.substring(0, at)).matches()) {
      return false;
    }
    final String[] labels = normalized.substring(at + 1).split("\\.", -1);
    return labels.length >= 2
        && Arrays.stream(labels).allMatch(label -> DOMAIN_LABEL.matcher(label).matches());
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
