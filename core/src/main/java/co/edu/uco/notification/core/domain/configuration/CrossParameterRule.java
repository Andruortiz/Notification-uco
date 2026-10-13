package co.edu.uco.notification.core.domain.configuration;

import java.util.Map;
import java.util.Optional;

public interface CrossParameterRule {

  String name();

  default boolean blocksStartup() {
    return true;
  }

  Optional<String> violation(Map<String, Long> values, FixedConfiguration fixed);
}
