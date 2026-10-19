package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record SessionCredential(String value) {

  public SessionCredential {
    Preconditions.requireNonBlank(value, "SessionCredential value must not be blank");
  }

  @Override
  public String toString() {
    return "SessionCredential[value=***]";
  }
}
