package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record TenantId(String value) {

  public TenantId {
    Preconditions.requireNonBlank(value, "TenantId must not be blank");
  }

  public static TenantId of(final String value) {
    return new TenantId(value);
  }
}
