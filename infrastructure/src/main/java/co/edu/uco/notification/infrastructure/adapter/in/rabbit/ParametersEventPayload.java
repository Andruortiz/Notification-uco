package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ParametersEventPayload(Long version, Map<String, Object> values) {

  public ParametersEventPayload {
    values = values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  @Override
  public Map<String, Object> values() {
    return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public ConfigurationChange toChange() {
    if (version == null || values == null) {
      throw new IllegalArgumentException("parameters event requires version and values");
    }
    return new ConfigurationChange(version, values);
  }
}
