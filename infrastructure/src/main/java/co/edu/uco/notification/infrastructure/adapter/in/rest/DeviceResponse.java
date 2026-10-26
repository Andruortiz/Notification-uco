package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.policy.TokenMask;
import java.time.Instant;

public record DeviceResponse(
    String deviceId,
    String recipientId,
    String platform,
    String token,
    String originComponent,
    Instant createdAt,
    Instant lastSeenAt) {

  static DeviceResponse from(final Device device, final boolean unmasked) {
    return new DeviceResponse(
        device.deviceId(),
        device.recipientIdentityId(),
        device.platform().name(),
        unmasked ? device.token() : TokenMask.mask(device.token()),
        device.originComponent(),
        device.createdAt(),
        device.lastSeenAt());
  }
}
