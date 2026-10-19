package co.edu.uco.notification.infrastructure.config;

import java.util.List;

final class CorsOrigins {

  private static final String WILDCARD = "*";

  private CorsOrigins() {}

  static void requireExplicit(final List<String> allowedOrigins) {
    final boolean wildcard =
        allowedOrigins.stream().map(String::trim).anyMatch(origin -> origin.contains(WILDCARD));
    if (wildcard) {
      throw new IllegalStateException(
          "notification.cors.allowed-origins must list explicit origins; a wildcard cannot be combined with credentials");
    }
  }
}
