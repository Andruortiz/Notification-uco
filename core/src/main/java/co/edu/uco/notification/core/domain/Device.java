package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;

public record Device(
    String deviceId,
    TenantId tenantId,
    String recipientIdentityId,
    DevicePlatform platform,
    String token,
    String originComponent,
    Instant createdAt,
    Instant lastSeenAt) {

  public static final int MAX_TOKEN_LENGTH = 4096;

  public Device {
    Preconditions.requireNonBlank(deviceId, "deviceId must not be blank");
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonBlank(recipientIdentityId, "recipientIdentityId must not be blank");
    Preconditions.requireNonNull(platform, "platform must not be null");
    Preconditions.requireNonBlank(token, "token must not be blank");
    Preconditions.requireTrue(token.length() <= MAX_TOKEN_LENGTH, "token is too long");
    Preconditions.requireNonBlank(originComponent, "originComponent must not be blank");
    Preconditions.requireNonNull(createdAt, "createdAt must not be null");
    Preconditions.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
  }

  @Override
  public String toString() {
    return "Device[deviceId=" + deviceId + ", tenantId=" + tenantId.value() + "]";
  }
}
