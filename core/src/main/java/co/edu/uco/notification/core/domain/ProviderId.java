package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.utils.Preconditions;

public record ProviderId(String value) {

  public ProviderId {
    Preconditions.requireNonBlank(value, "ProviderId must not be blank");
  }

  public static ProviderId of(final String value) {
    return new ProviderId(value);
  }
}
