package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record AuthenticatedPrincipal(String subject, TenantId tenantId, Role role) {

  public AuthenticatedPrincipal {
    Preconditions.requireNonBlank(subject, "AuthenticatedPrincipal subject must not be blank");
    Preconditions.requireNonNull(tenantId, "AuthenticatedPrincipal tenantId must not be null");
    Preconditions.requireNonNull(role, "AuthenticatedPrincipal role must not be null");
  }
}
