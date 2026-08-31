package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.utils.Preconditions;
import java.util.UUID;

public record NotificationId(String value) {

  public NotificationId {
    Preconditions.requireNonBlank(value, "NotificationId must not be blank");
  }

  public static NotificationId newId() {
    return new NotificationId(UUID.randomUUID().toString());
  }

  public static NotificationId of(final String value) {
    return new NotificationId(value);
  }
}
