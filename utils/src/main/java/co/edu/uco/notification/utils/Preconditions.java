package co.edu.uco.notification.utils;

import java.util.Objects;

public final class Preconditions {

  private Preconditions() {}

  public static <T> T requireNonNull(final T value, final String message) {
    return Objects.requireNonNull(value, message);
  }

  public static String requireNonBlank(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  public static void requireTrue(final boolean condition, final String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
