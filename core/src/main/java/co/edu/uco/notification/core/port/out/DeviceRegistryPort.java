package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.RecipientIdentity;
import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DeviceRegistryPort {

  record Saved(Device device, boolean created) {}

  Mono<RecipientIdentity> findOrCreateIdentity(TenantId tenantId, String subject);

  Mono<Saved> save(
      TenantId tenantId,
      RecipientIdentity identity,
      DevicePlatform platform,
      String token,
      String originComponent);

  Flux<Device> findBySubject(TenantId tenantId, String subject);

  Mono<Boolean> remove(TenantId tenantId, String deviceId, String subjectOrNull);

  Mono<Void> eraseBySubject(TenantId tenantId, String subject);
}
