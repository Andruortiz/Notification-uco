package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class ChannelCatalogCache {

  private final AtomicReference<Map<String, ChannelRoute>> snapshot =
      new AtomicReference<>(Map.of());

  public Map<String, ChannelRoute> snapshot() {
    return snapshot.get();
  }

  public void replace(final Map<String, ChannelRoute> routes) {
    Preconditions.requireNonNull(routes, "routes must not be null");
    snapshot.set(Map.copyOf(routes));
  }
}
