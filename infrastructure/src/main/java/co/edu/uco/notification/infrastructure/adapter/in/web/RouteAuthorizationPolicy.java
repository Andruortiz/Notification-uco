package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.config.PepDelegation;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class RouteAuthorizationPolicy {

  private record Rule(HttpMethod method, Predicate<String> path, Role minimumRole) {}

  private final List<Rule> rules =
      List.of(
          new Rule(HttpMethod.POST, path -> path.equals("/notifications"), Role.CLIENTE),
          new Rule(HttpMethod.GET, path -> path.equals("/notifications"), Role.OPERADOR),
          new Rule(HttpMethod.POST, path -> path.equals("/notifications/batches"), Role.CLIENTE),
          new Rule(HttpMethod.GET, path -> path.matches("^/notifications/[^/:]+$"), Role.CLIENTE),
          new Rule(
              HttpMethod.POST,
              path -> path.matches("^/notifications/[^/:]+:retry$"),
              Role.OPERADOR),
          new Rule(HttpMethod.GET, path -> path.equals("/notifications/stream"), Role.CLIENTE),
          new Rule(
              HttpMethod.POST, path -> path.equals("/notifications/stream/tickets"), Role.CLIENTE),
          new Rule(HttpMethod.POST, path -> path.equals("/attachment-uploads"), Role.CLIENTE),
          new Rule(
              HttpMethod.POST, path -> path.equals("/attachment-upload-completions"), Role.CLIENTE),
          new Rule(
              HttpMethod.GET, path -> path.matches("^/attachment-uploads/[^/:]+$"), Role.CLIENTE),
          new Rule(HttpMethod.GET, path -> path.equals("/channels"), Role.CLIENTE),
          new Rule(HttpMethod.GET, path -> path.equals("/providers"), Role.CLIENTE),
          new Rule(HttpMethod.GET, path -> path.equals("/configuration"), Role.ADMINISTRADOR),
          new Rule(HttpMethod.POST, path -> path.equals("/channels:register"), Role.ADMINISTRADOR),
          new Rule(HttpMethod.POST, path -> path.equals("/providers:register"), Role.ADMINISTRADOR),
          new Rule(
              HttpMethod.GET,
              path -> path.matches("^/recipients/[^/:]+/preferences$"),
              Role.CLIENTE),
          new Rule(
              HttpMethod.POST,
              path -> path.matches("^/recipients/[^/:]+:updatePreferences$"),
              Role.CLIENTE));

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
    return rules.stream()
        .filter(rule -> rule.method().equals(method) && rule.path().test(path))
        .findFirst()
        .map(Rule::minimumRole)
        .orElse(Role.ADMINISTRADOR);
  }
}
