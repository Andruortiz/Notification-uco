package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ConfigurationDescription;
import java.time.Instant;
import java.util.List;

public record ConfigurationResponse(
    long version,
    String source,
    Instant adoptedAt,
    List<String> pendingRestart,
    List<ParameterDescriptorResponse> parameters) {

  static ConfigurationResponse from(final ConfigurationDescription description) {
    return new ConfigurationResponse(
        description.version(),
        description.source().name(),
        description.adoptedAt(),
        description.pendingRestart().stream().sorted().toList(),
        description.parameters().stream().map(ParameterDescriptorResponse::from).toList());
  }
}
