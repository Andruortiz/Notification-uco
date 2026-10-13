package co.edu.uco.notification.core.domain.configuration;

import java.util.List;

public record ValidationResult(List<String> violations) {

  public ValidationResult {
    violations = violations == null ? List.of() : List.copyOf(violations);
  }

  public static ValidationResult valid() {
    return new ValidationResult(List.of());
  }

  public boolean isValid() {
    return violations.isEmpty();
  }

  public String summary() {
    return String.join("; ", violations);
  }
}
