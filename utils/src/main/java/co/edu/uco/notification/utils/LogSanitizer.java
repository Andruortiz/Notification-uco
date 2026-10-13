package co.edu.uco.notification.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogSanitizer {

  public static final String MASK = "***";
  public static final String REDACTED = "[REDACTED]";
  public static final int MAX_LENGTH = 256;

  private static final int VISIBLE_CHARACTERS = 4;
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
  private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
  private static final Pattern SECRET_ASSIGNMENT =
      Pattern.compile(
          "(?i)((?:api[-_]?key|authorization|password|passwd|secret|token|credential|auth[-_]?token)"
              + "[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\"'&}]+");
  private static final Pattern PHONE = Pattern.compile("\\+[1-9]\\d{6,14}");

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

  public static String scrub(final String text) {
    if (text == null) {
      return null;
    }
    String result = JWT.matcher(text).replaceAll(REDACTED);
    result = BEARER.matcher(result).replaceAll("$1" + REDACTED);
    result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1" + REDACTED);
    result = EMAIL.matcher(result).replaceAll(MASK + "$1");
    final Matcher phones = PHONE.matcher(result);
    return phones.replaceAll(match -> Matcher.quoteReplacement(PhoneNumbers.mask(match.group())));
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
