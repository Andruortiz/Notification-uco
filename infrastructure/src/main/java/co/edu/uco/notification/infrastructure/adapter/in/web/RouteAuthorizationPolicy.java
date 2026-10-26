package co.edu.uco.notification.infrastructure.adapter.in.web;

import static co.edu.uco.notification.core.domain.valueobject.Role.ADMINISTRADOR_APLICACION;
import static co.edu.uco.notification.core.domain.valueobject.Role.ADMINISTRADOR_PLATAFORMA;
import static co.edu.uco.notification.core.domain.valueobject.Role.CLIENTE;
import static co.edu.uco.notification.core.domain.valueobject.Role.EDITOR_PLANTILLAS;
import static co.edu.uco.notification.core.domain.valueobject.Role.OPERADOR;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.config.PepDelegation;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class RouteAuthorizationPolicy {

  private record Route(HttpMethod method, String path) {}

  private static final Set<Role> UNDECLARED = Set.of(ADMINISTRADOR_PLATAFORMA);

  private final Map<Route, Set<Role>> rules =
      Map.ofEntries(
          rule(
              HttpMethod.POST,
              "/notifications",
              CLIENTE,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.GET,
              "/notifications",
              OPERADOR,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.POST,
              "/notifications/batches",
              CLIENTE,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.GET,
              "/notifications/detail",
              CLIENTE,
              OPERADOR,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.GET,
              "/notifications/timeline",
              OPERADOR,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/notification-retries", OPERADOR, ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/notification-resends", OPERADOR, ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.GET,
              "/notifications/stream",
              CLIENTE,
              OPERADOR,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/attachment-uploads", CLIENTE, ADMINISTRADOR_APLICACION),
          rule(
              HttpMethod.POST, "/attachment-upload-completions", CLIENTE, ADMINISTRADOR_APLICACION),
          rule(HttpMethod.GET, "/attachment-uploads/status", CLIENTE, ADMINISTRADOR_APLICACION),
          rule(HttpMethod.GET, "/channels", Role.values()),
          rule(HttpMethod.GET, "/providers", Role.values()),
          rule(
              HttpMethod.GET,
              "/provider-health",
              OPERADOR,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.GET, "/configuration", ADMINISTRADOR_APLICACION, ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/channels", ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/providers", ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/provider-connection-tests", ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.GET, "/recipients/preferences", CLIENTE, ADMINISTRADOR_APLICACION),
          rule(HttpMethod.PUT, "/recipients/preferences", CLIENTE, ADMINISTRADOR_APLICACION),
          rule(
              HttpMethod.POST,
              "/recipients/undeliverable-lookups",
              OPERADOR,
              ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/recipients/undeliverable-releases", ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/devices", CLIENTE),
          rule(HttpMethod.GET, "/devices", CLIENTE),
          rule(HttpMethod.POST, "/device-removals", CLIENTE),
          rule(HttpMethod.POST, "/device-erasures", ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/templates", EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.PUT, "/templates", EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.GET,
              "/templates",
              CLIENTE,
              OPERADOR,
              EDITOR_PLANTILLAS,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.GET,
              "/templates/detail",
              CLIENTE,
              OPERADOR,
              EDITOR_PLANTILLAS,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA),
          rule(HttpMethod.POST, "/template-archivals", EDITOR_PLANTILLAS, ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.POST,
              "/template-variable-detections",
              EDITOR_PLANTILLAS,
              ADMINISTRADOR_PLATAFORMA),
          rule(
              HttpMethod.POST,
              "/template-renders",
              CLIENTE,
              EDITOR_PLANTILLAS,
              ADMINISTRADOR_APLICACION,
              ADMINISTRADOR_PLATAFORMA));

  private static Map.Entry<Route, Set<Role>> rule(
      final HttpMethod method, final String path, final Role... roles) {
    return Map.entry(new Route(method, path), Set.of(roles));
  }

  private final boolean delegatedToPep;

  public RouteAuthorizationPolicy() {
    this(false);
  }

  @Autowired
  public RouteAuthorizationPolicy(final Environment environment) {
    this(PepDelegation.isActive(environment));
  }

  private RouteAuthorizationPolicy(final boolean delegatedToPep) {
    this.delegatedToPep = delegatedToPep;
  }

  public Set<Role> allowedRolesFor(final HttpMethod method, final String path) {
    if (delegatedToPep) {
      return EnumSet.allOf(Role.class);
    }
    return rules.getOrDefault(new Route(method, path), UNDECLARED);
  }
}
