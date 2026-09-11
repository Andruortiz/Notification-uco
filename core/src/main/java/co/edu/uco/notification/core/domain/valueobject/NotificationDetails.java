package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record NotificationDetails(NotificationContent content, Priority priority) {

  public NotificationDetails {

    Preconditions.requireNonNull(content, "content must not be null");
    Preconditions.requireNonNull(priority, "priority must not be null");
  }
}
