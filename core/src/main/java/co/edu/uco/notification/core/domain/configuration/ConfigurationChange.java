package co.edu.uco.notification.core.domain.configuration;

import co.edu.uco.notification.utils.Preconditions;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConfigurationChange(long version, Map<String, Object> values) {

  public ConfigurationChange {
    Preconditions.requireNonNull(values, "values must not be null");
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
