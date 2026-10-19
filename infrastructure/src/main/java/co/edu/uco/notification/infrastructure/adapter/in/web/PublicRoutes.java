package co.edu.uco.notification.infrastructure.adapter.in.web;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
public class PublicRoutes {

  public static final List<String> DOCUMENTATION =
      List.of(
          "/openapi/api-notificaciones.yaml",
          "/v3/api-docs",
          "/v3/api-docs/swagger-config",
          "/swagger-ui.html",
          "/swagger-ui/**");

  private static final String ACTUATOR_PATH = "/actuator";

  private final List<PathPattern> patterns;

  @Autowired
  public PublicRoutes(
      @Value("${security.pep.enforcement.public-paths:}") final List<String> configured) {
    final PathPatternParser parser = new PathPatternParser();
    this.patterns =
        configured.stream()
            .map(String::trim)
            .filter(path -> !path.isEmpty())
            .filter(path -> !isActuator(path))
            .map(parser::parse)
            .toList();
  }

  public static PublicRoutes documentation() {
    return new PublicRoutes(DOCUMENTATION);
  }

  public boolean matches(final String path) {
    final PathContainer container = PathContainer.parsePath(path);
    return patterns.stream().anyMatch(pattern -> pattern.matches(container));
  }

  private static boolean isActuator(final String path) {
    return path.equals(ACTUATOR_PATH) || path.startsWith(ACTUATOR_PATH + "/");
  }
}
