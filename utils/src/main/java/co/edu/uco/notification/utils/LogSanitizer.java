package co.edu.uco.notification.utils;

public final class LogSanitizer {

  public static final String MASK = "***";
  public static final String REDACTED = "[REDACTED]";
  public static final int MAX_LENGTH = 256;

  private static final int VISIBLE_CHARACTERS = 4;

  private LogSanitizer() {}

  public static String maskRecipient(final String address) {
    if (address == null || address.isBlank()) {
      return MASK;
    }
    final int at = address.lastIndexOf('@');
    if (at > 0) {
      return MASK + safe(address.substring(at));
    }
    if (address.startsWith("+")) {
      return PhoneNumbers.mask(address);
    }
    if (address.length() <= VISIBLE_CHARACTERS) {
      return MASK;
    }
    return MASK + safe(address.substring(address.length() - VISIBLE_CHARACTERS));
  }

  public static String redact(final Object ignored) {
    return REDACTED;
  }

  public static String safe(final String value) {
    if (value == null) {
      return "null";
    }
    final StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
    value
        .codePoints()
        .limit(MAX_LENGTH)
        .forEach(code -> safe.appendCodePoint(Character.isISOControl(code) ? '_' : code));
    return safe.toString();
  }

  public static String redactSecrets(final String text, final String... secrets) {
    if (text == null) {
      return null;
    }
    String result = text;
    for (final String secret : secrets) {
      if (secret != null && !secret.isBlank()) {
        result = result.replace(secret, REDACTED);
      }
    }
    return result;
  }
}
