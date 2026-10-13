package co.edu.uco.notification.core.domain.configuration;

import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Optional;

public record ParameterDescriptor(
    String key,
    ParameterType type,
    long defaultValue,
    long min,
    long max,
    ParameterScope scope,
    List<String> scopeIds,
    AdoptionMode adoption) {

  public ParameterDescriptor {
    Preconditions.requireNonBlank(key, "key must not be blank");
    Preconditions.requireNonNull(type, "type must not be null");
    Preconditions.requireNonNull(scope, "scope must not be null");
    Preconditions.requireNonNull(adoption, "adoption must not be null");
    Preconditions.requireTrue(min <= max, "min must not exceed max");
    Preconditions.requireTrue(
        defaultValue >= min && defaultValue <= max, "defaultValue must be within the range");
    scopeIds = scopeIds == null ? List.of() : List.copyOf(scopeIds);
    Preconditions.requireTrue(
        scope == ParameterScope.GLOBAL ? scopeIds.isEmpty() : !scopeIds.isEmpty(),
        "scopeIds must be empty for GLOBAL scope and present otherwise");
  }

  public static ParameterDescriptor global(
      final String key, final long defaultValue, final long min, final long max) {
    return new ParameterDescriptor(
        key,
        ParameterType.INTEGER,
        defaultValue,
        min,
        max,
        ParameterScope.GLOBAL,
        List.of(),
        AdoptionMode.HOT);
  }

  public static ParameterDescriptor forProvider(
      final String key,
      final String providerId,
      final long defaultValue,
      final long min,
      final long max) {
    return new ParameterDescriptor(
        key,
        ParameterType.INTEGER,
        defaultValue,
        min,
        max,
        ParameterScope.PROVIDER,
        List.of(providerId),
        AdoptionMode.HOT);
  }

  public ParameterDescriptor withAdoption(final AdoptionMode newAdoption) {
    return new ParameterDescriptor(key, type, defaultValue, min, max, scope, scopeIds, newAdoption);
  }

  public Optional<Long> asInteger(final Object raw) {
    if (raw instanceof Long
        || raw instanceof Integer
        || raw instanceof Short
        || raw instanceof Byte) {
      return Optional.of(((Number) raw).longValue());
    }
    if (raw instanceof Double || raw instanceof Float) {
      final double value = ((Number) raw).doubleValue();
      if (value == Math.rint(value) && !Double.isInfinite(value)) {
        return Optional.of((long) value);
      }
    }
    return Optional.empty();
  }

  public Optional<String> violation(final Object raw) {
    final Optional<Long> value = asInteger(raw);
    if (value.isEmpty()) {
      return Optional.of(key + " must be an integer");
    }
    if (value.get() < min || value.get() > max) {
      return Optional.of(key + " must be between " + min + " and " + max);
    }
    return Optional.empty();
  }
}
