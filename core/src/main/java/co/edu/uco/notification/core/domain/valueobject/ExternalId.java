package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record ExternalId(String value) {

  public ExternalId {
    Preconditions.requireNonBlank(value, "ExternalId must not be blank");
  }

  public static ExternalId of(final String value) {
    return new ExternalId(value);
  }
}
