package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record RecipientIdentity(
    String recipientIdentityId, TenantId tenantId, String subject, Instant createdAt) {

  public RecipientIdentity {
    Preconditions.requireNonBlank(recipientIdentityId, "recipientIdentityId must not be blank");
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(createdAt, "createdAt must not be null");
  }
}
