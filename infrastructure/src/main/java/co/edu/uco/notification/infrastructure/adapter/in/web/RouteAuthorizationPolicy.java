package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.config.PepDelegation;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class RouteAuthorizationPolicy {

  private record Route(HttpMethod method, String path) {}

  private final Map<Route, Role> rules =
      Map.ofEntries(
          rule(HttpMethod.POST, "/notifications", Role.CLIENTE),
          rule(HttpMethod.GET, "/notifications", Role.OPERADOR),
          rule(HttpMethod.POST, "/notifications/batches", Role.CLIENTE),
          rule(HttpMethod.GET, "/notifications/detail", Role.CLIENTE),
          rule(HttpMethod.POST, "/notification-retries", Role.OPERADOR),
          rule(HttpMethod.GET, "/notifications/stream", Role.CLIENTE),
          rule(HttpMethod.POST, "/notifications/stream/tickets", Role.CLIENTE),
          rule(HttpMethod.POST, "/attachment-uploads", Role.CLIENTE),
          rule(HttpMethod.POST, "/attachment-upload-completions", Role.CLIENTE),
          rule(HttpMethod.GET, "/attachment-uploads/status", Role.CLIENTE),
          rule(HttpMethod.GET, "/channels", Role.CLIENTE),
          rule(HttpMethod.GET, "/providers", Role.CLIENTE),
          rule(HttpMethod.GET, "/configuration", Role.ADMINISTRADOR),
          rule(HttpMethod.POST, "/channels", Role.ADMINISTRADOR),
          rule(HttpMethod.POST, "/providers", Role.ADMINISTRADOR),
          rule(HttpMethod.GET, "/recipients/preferences", Role.CLIENTE),
          rule(HttpMethod.PUT, "/recipients/preferences", Role.CLIENTE));

  private static Map.Entry<Route, Role> rule(
      final HttpMethod method, final String path, final Role minimumRole) {
    return Map.entry(new Route(method, path), minimumRole);
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

  public Role minimumRoleFor(final HttpMethod method, final String path) {
    if (delegatedToPep) {
      return Role.CLIENTE;
    }
    return rules.getOrDefault(new Route(method, path), Role.ADMINISTRADOR);
  }
}
