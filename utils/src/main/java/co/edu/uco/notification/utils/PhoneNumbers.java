package co.edu.uco.notification.utils;

import java.util.regex.Pattern;

public final class PhoneNumbers {

  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{1,14}$");
  private static final String MASK = "***";
  private static final int VISIBLE_DIGITS = 4;

  private PhoneNumbers() {}

  public static boolean isE164(final String value) {
    return value != null && E164.matcher(value).matches();
  }

  public static String mask(final String value) {
    if (value == null) {
      return MASK;
    }
    final String digits = value.replaceAll("\\D", "");
    if (digits.length() <= VISIBLE_DIGITS) {
      return MASK;
    }
    return MASK + digits.substring(digits.length() - VISIBLE_DIGITS);
  }
}
