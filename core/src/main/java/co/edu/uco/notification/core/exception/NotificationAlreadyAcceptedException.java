package co.edu.uco.notification.core.exception;

import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;

public class NotificationAlreadyAcceptedException extends RuntimeException {

  public NotificationAlreadyAcceptedException(
      final TenantId tenantId, final ExternalId externalId) {
    super(
        "Notification already accepted for tenant: "
            + tenantId.value()
            + " externalId: "
            + externalId.value());
  }
}
