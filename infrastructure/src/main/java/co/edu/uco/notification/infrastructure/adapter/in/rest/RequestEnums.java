package co.edu.uco.notification.infrastructure.adapter.in.rest;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Convierte los valores de enumeración que llegan en una solicitud, y siempre falla con un mensaje
 * que nombra el campo y los valores admitidos, sin exponer nombres de clases internas.
 */
final class RequestEnums {

  private RequestEnums() {}

  static <E extends Enum<E>> E required(
      final Class<E> type, final String field, final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return parse(type, field, value);
  }

  static <E extends Enum<E>> E optional(
      final Class<E> type, final String field, final String value) {
    return value == null ? null : parse(type, field, value);
  }

  private static <E extends Enum<E>> E parse(
      final Class<E> type, final String field, final String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (final IllegalArgumentException e) {
      final String allowed =
          Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
      throw new IllegalArgumentException(field + " must be one of " + allowed);
    }
  }
}
