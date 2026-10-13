package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record RecipientId(String value) {

  public RecipientId {
    Preconditions.requireNonBlank(value, "RecipientId must not be blank");
  }

  public static RecipientId of(final String value) {
    return new RecipientId(value);
  }
}
