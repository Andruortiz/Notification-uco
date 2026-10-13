package co.edu.uco.notification.infrastructure.support;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenIssuer;
import java.time.Duration;

public final class TestTokens {

  public static final String SECRET = "notification-uco-dev-only-secret-change-me-0123456789abcdef";

  private static final LocalJwtTokenIssuer ISSUER = new LocalJwtTokenIssuer(SECRET);

  private TestTokens() {}

  public static String bearer(final String tenantId) {
    return bearer(tenantId, Role.ADMINISTRADOR);
  }

  public static String bearer(final String tenantId, final Role role) {
    return "Bearer "
        + ISSUER.issue(TenantId.of(tenantId), role, "test-client", Duration.ofMinutes(15));
  }
}
