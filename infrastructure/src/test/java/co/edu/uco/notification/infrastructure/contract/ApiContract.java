package co.edu.uco.notification.infrastructure.contract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

public final class ApiContract {

  public static final String RESOURCE = "static/openapi/api-notificaciones.yaml";
  public static final String PATH_REGEX = "^/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$";

  private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
  public static final List<String> ROLES =
      List.of(
          "ADMINISTRADOR_PLATAFORMA",
          "ADMINISTRADOR_APLICACION",
          "EDITOR_PLANTILLAS",
          "OPERADOR",
          "AUDITOR",
          "CLIENTE");

  public record Operation(
      String method,
      String path,
      List<String> allowedRoles,
      String status,
      String operationId,
      Map<String, Object> definition) {

    public boolean available() {
      return "available".equals(status);
    }

    public String key() {
      return method + " " + path;
    }
  }

  private static Map<String, Object> document;

  private ApiContract() {}

  public static synchronized Map<String, Object> document() {
    if (document == null) {
      try (InputStream input = ApiContract.class.getClassLoader().getResourceAsStream(RESOURCE)) {
        if (input == null) {
          throw new IllegalStateException("contract not found: " + RESOURCE);
        }
        document = new Yaml().load(input);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return document;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(final Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(final Object value) {
    return (List<Object>) value;
  }

  public static Map<String, Object> paths() {
    return map(document().get("paths"));
  }

  public static Map<String, Object> components(final String section) {
    return map(map(document().get("components")).get(section));
  }

  public static Map<String, Object> schemas() {
    return components("schemas");
  }

  public static Map<String, Object> info() {
    return map(document().get("info"));
  }

  public static List<Operation> operations() {
    final List<Operation> result = new ArrayList<>();
    for (final Map.Entry<String, Object> path : paths().entrySet()) {
      for (final Map.Entry<String, Object> entry : map(path.getValue()).entrySet()) {
        if (!METHODS.contains(entry.getKey())) {
          continue;
        }
        final Map<String, Object> definition = map(entry.getValue());
        result.add(
            new Operation(
                entry.getKey().toUpperCase(Locale.ROOT),
                path.getKey(),
                ApiContract.list(definition.get("x-allowed-roles")).stream()
                    .map(String.class::cast)
                    .toList(),
                (String) definition.get("x-status"),
                (String) definition.get("operationId"),
                definition));
      }
    }
    return result;
  }
}
