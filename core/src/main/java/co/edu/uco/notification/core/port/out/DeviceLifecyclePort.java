package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Mono;

public interface DeviceLifecyclePort {

  Mono<Void> invalidate(TenantId tenantId, String address);

  Mono<Void> touch(TenantId tenantId, String address);

  static DeviceLifecyclePort noop() {
    return new DeviceLifecyclePort() {
      @Override
      public Mono<Void> invalidate(final TenantId tenantId, final String address) {
        return Mono.empty();
      }

      @Override
      public Mono<Void> touch(final TenantId tenantId, final String address) {
        return Mono.empty();
      }
    };
  }
}
