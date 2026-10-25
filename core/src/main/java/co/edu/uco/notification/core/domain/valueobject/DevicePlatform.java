package co.edu.uco.notification.core.domain.valueobject;

public enum DevicePlatform {
  ANDROID,
  IOS,
  WEB;

  public static DevicePlatform of(final String value) {
    if (value == null) {
      throw new IllegalArgumentException("platform must not be null");
    }
    for (final DevicePlatform platform : values()) {
      if (platform.name().equals(value)) {
        return platform;
      }
    }
    throw new IllegalArgumentException("Unknown platform: " + value);
  }
}
