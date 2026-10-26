package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.util.Set;

public record AuthenticatedPrincipal(
    String subject, TenantId tenantId, Set<Role> roles, String clientId) {

  public AuthenticatedPrincipal(final String subject, final TenantId tenantId, final Role role) {
    this(subject, tenantId, Set.of(role), null);
  }

  public AuthenticatedPrincipal {
    Preconditions.requireNonBlank(subject, "AuthenticatedPrincipal subject must not be blank");
    Preconditions.requireNonNull(tenantId, "AuthenticatedPrincipal tenantId must not be null");
    Preconditions.requireNonNull(roles, "AuthenticatedPrincipal roles must not be null");
    if (roles.isEmpty()) {
      throw new IllegalArgumentException("AuthenticatedPrincipal roles must not be empty");
    }
    roles = Set.copyOf(roles);
  }

  public boolean seesUnmaskedData() {
    return roles.contains(Role.ADMINISTRADOR_PLATAFORMA)
        || roles.contains(Role.ADMINISTRADOR_APLICACION);
  }
}
