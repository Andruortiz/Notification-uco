package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class MongoChannelCatalogAdapterTest {

  private final ChannelCatalogCache cache = new ChannelCatalogCache();
  private final MongoChannelCatalogAdapter adapter = new MongoChannelCatalogAdapter(cache);

  @Test
  void findActiveRouteReadsFromTheCacheSnapshot() {
    final ChannelRoute route =
        new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), "{}");
    cache.replace(Map.of("EMAIL", route));

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("EMAIL"), null))
        .expectNext(route)
        .verifyComplete();
  }

  @Test
  void lookupIsCaseInsensitive() {
    final ChannelRoute route =
        new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), null);
    cache.replace(Map.of("EMAIL", route));

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("email"), null))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void findActiveRouteIsEmptyForUnknownChannel() {
    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("SMS"), null)).verifyComplete();
  }

  @Test
  void findActiveRouteRejectsNullChannel() {
    assertThrows(NullPointerException.class, () -> adapter.findActiveRoute(null, null));
  }

  @Test
  void findActiveRouteIsGlobalAcrossTenants() {
    final ChannelRoute route =
        new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), null);
    cache.replace(Map.of("EMAIL", route));

    final ChannelRoute forTenantA =
        adapter.findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("tenant-a")).block();
    final ChannelRoute forTenantB =
        adapter.findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("tenant-b")).block();

    assertEquals(forTenantA, forTenantB);
  }

  @Test
  void constructorRejectsNullCache() {
    assertThrows(NullPointerException.class, () -> new MongoChannelCatalogAdapter(null));
  }
}
