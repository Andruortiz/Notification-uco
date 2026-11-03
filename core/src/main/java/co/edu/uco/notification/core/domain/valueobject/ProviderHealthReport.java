package co.edu.uco.notification.core.domain.valueobject;

import java.time.Instant;
import java.util.List;

public record ProviderHealthReport(
    Instant generatedAt, long windowSeconds, String grafanaUrl, List<ProviderHealth> providers) {

  public ProviderHealthReport {
    providers = List.copyOf(providers);
  }
}
