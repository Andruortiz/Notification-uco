package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.port.in.ParameterDescription;
import java.util.List;

public record ParameterDescriptorResponse(
    String key,
    String type,
    long defaultValue,
    long currentValue,
    long min,
    long max,
    String scope,
    List<String> scopeIds,
    String adoption) {

  public ParameterDescriptorResponse {
    scopeIds = scopeIds == null ? null : List.copyOf(scopeIds);
  }

  @Override
  public List<String> scopeIds() {
    return scopeIds == null ? null : List.copyOf(scopeIds);
  }

  static ParameterDescriptorResponse from(final ParameterDescription description) {
    final ParameterDescriptor descriptor = description.descriptor();
    return new ParameterDescriptorResponse(
        descriptor.key(),
        descriptor.type().name(),
        descriptor.defaultValue(),
        description.currentValue(),
        descriptor.min(),
        descriptor.max(),
        descriptor.scope().name(),
        descriptor.scopeIds(),
        descriptor.adoption().name());
  }
}
