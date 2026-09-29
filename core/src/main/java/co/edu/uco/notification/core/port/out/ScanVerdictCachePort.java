package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.ScanVerdict;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import reactor.core.publisher.Mono;

public interface ScanVerdictCachePort {

  Mono<ScanVerdict> find(TenantId tenantId, Sha256Digest sha256);

  Mono<Void> save(TenantId tenantId, Sha256Digest sha256, ScanVerdict verdict);
}
