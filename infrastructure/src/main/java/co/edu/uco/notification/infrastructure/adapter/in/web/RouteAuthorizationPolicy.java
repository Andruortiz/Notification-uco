package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.core.domain.valueobject.Role;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class RouteAuthorizationPolicy {

  private record Rule(HttpMethod method, Predicate<String> path, Role minimumRole) {}

  private final List<Rule> rules =
      List.of(
          new Rule(HttpMethod.POST, path -> path.equals("/notifications"), Role.CLIENTE),
          new Rule(HttpMethod.GET, path -> path.equals("/notifications"), Role.OPERADOR),
          new Rule(HttpMethod.POST, path -> path.equals("/notifications:sendBatch"), Role.CLIENTE),
          new Rule(HttpMethod.GET, path -> path.matches("^/notifications/[^/:]+$"), Role.CLIENTE),
          new Rule(
              HttpMethod.POST,
              path -> path.matches("^/notifications/[^/:]+:retry$"),
              Role.OPERADOR),
          new Rule(HttpMethod.GET, path -> path.equals("/notifications:subscribe"), Role.CLIENTE),
          new Rule(
              HttpMethod.POST, path -> path.equals("/notifications:subscribeTicket"), Role.CLIENTE),
          new Rule(HttpMethod.POST, path -> path.equals("/attachment-uploads"), Role.CLIENTE),
          new Rule(
              HttpMethod.POST,
              path -> path.matches("^/attachment-uploads/[^/:]+:complete$"),
              Role.CLIENTE),
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

  public Role minimumRoleFor(final HttpMethod method, final String path) {
    return rules.stream()
        .filter(rule -> rule.method().equals(method) && rule.path().test(path))
        .findFirst()
        .map(Rule::minimumRole)
        .orElse(Role.ADMINISTRADOR);
  }
}
