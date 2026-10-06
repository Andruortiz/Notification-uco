package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ConfigurationDescription(
    long version,
    ConfigurationSource source,
    Instant adoptedAt,
    Set<String> pendingRestart,
    List<ParameterDescription> parameters) {

  public ConfigurationDescription {
    pendingRestart = pendingRestart == null ? Set.of() : Set.copyOf(pendingRestart);
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
  }
}
