package co.edu.uco.notification.infrastructure.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

public final class FakePdpAdmin implements AutoCloseable {

  public static final String SESSION_COOKIE = "SECURITY_BASELINE_SESSION";
  public static final String CSRF_COOKIE = "XSRF-TOKEN";
  public static final String CSRF_HEADER = "X-XSRF-TOKEN";
  public static final String APPLICATION_ID = "app-notification";
  public static final String OTHER_APPLICATION_ID = "app-other";

  private static final Pattern RESOURCE_PATH_CHARACTERS = Pattern.compile("^/[A-Za-z0-9._~{}/-]*$");
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
  private static final Pattern APPLICATION_RESOURCES =
      Pattern.compile("^/api/v1/applications/([^/]+)/resources$");
  private static final Pattern ROLE_RESOURCES =
      Pattern.compile("^/api/v1/roles/([^/]+)/resources$");
  private static final Pattern PROFILE_ROLES = Pattern.compile("^/api/v1/profiles/([^/]+)/roles$");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpServer server;
  private final String sessionValue;
  private final String csrfToken = UUID.randomUUID().toString();
  private final List<Map<String, Object>> applications = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> resources = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> roles = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> profiles = new CopyOnWriteArrayList<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> credentialsSeen = new CopyOnWriteArrayList<>();

  public FakePdpAdmin(final String sessionValue) {
    this.sessionValue = sessionValue;
    applications.add(application(APPLICATION_ID, "notification-service"));
    applications.add(application(OTHER_APPLICATION_ID, "other-service"));
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", this::handle);
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public List<String> requests() {
    return List.copyOf(requests);
  }

  public List<String> mutations() {
    return requests.stream().filter(request -> !request.startsWith("GET ")).toList();
  }

  public List<String> credentialsSeen() {
    return List.copyOf(credentialsSeen);
  }

  public void clearRequests() {
    requests.clear();
  }

  public List<Map<String, Object>> resources(final String applicationId) {
    return resources.stream().filter(r -> applicationId.equals(r.get("applicationId"))).toList();
  }

  public List<Map<String, Object>> roles(final String applicationId) {
    return roles.stream().filter(r -> applicationId.equals(r.get("applicationId"))).toList();
  }

  public List<Map<String, Object>> profiles(final String applicationId) {
    return profiles.stream().filter(r -> applicationId.equals(r.get("applicationId"))).toList();
  }

  public Map<String, Object> seedResource(
      final String applicationId, final String method, final String path) {
    final Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("id", UUID.randomUUID().toString());
    resource.put("applicationId", applicationId);
    resource.put("method", method);
    resource.put("path", path);
    resources.add(resource);
    return resource;
  }

  public Map<String, Object> seedRole(final String applicationId, final String name) {
    final Map<String, Object> role = new LinkedHashMap<>();
    role.put("id", UUID.randomUUID().toString());
    role.put("name", name);
    role.put("scope", "APPLICATION");
    role.put("applicationId", applicationId);
    role.put("resourceIds", new ArrayList<String>());
    roles.add(role);
    return role;
  }

  private static Map<String, Object> application(final String id, final String name) {
    final Map<String, Object> application = new LinkedHashMap<>();
    application.put("id", id);
    application.put("name", name);
    application.put("description", name);
    application.put("baseUrl", "http://localhost");
    return application;
  }

  private void handle(final HttpExchange exchange) throws IOException {
    final String method = exchange.getRequestMethod();
    final URI uri = exchange.getRequestURI();
    final String path = uri.getPath();
    requests.add(method + " " + path);
    final String cookies = exchange.getRequestHeaders().getFirst("Cookie");
    credentialsSeen.add(String.valueOf(cookies));
    if (cookies == null || !cookies.contains(SESSION_COOKIE + "=" + sessionValue)) {
      send(exchange, 401, Map.of("detail", "sesion no valida"));
      return;
    }
    if (!"GET".equals(method)) {
      final String header = exchange.getRequestHeaders().getFirst(CSRF_HEADER);
      if (header == null
          || !cookies.contains(CSRF_COOKIE + "=" + header)
          || !header.equals(csrfToken)) {
        send(exchange, 403, Map.of("detail", "CSRF"));
        return;
      }
    }
    final Map<String, Object> body = readBody(exchange);
    route(exchange, method, path, uri.getQuery(), body);
  }

  private void route(
      final HttpExchange exchange,
      final String method,
      final String path,
      final String query,
      final Map<String, Object> body)
      throws IOException {
    if ("GET".equals(method) && "/api/v1/session".equals(path)) {
      exchange.getResponseHeaders().add("Set-Cookie", CSRF_COOKIE + "=" + csrfToken + "; Path=/");
      send(exchange, 200, envelope(Map.of("subject", "admin")));
      return;
    }
    if ("GET".equals(method) && "/api/v1/applications".equals(path)) {
      send(exchange, 200, envelope(page(applications, query)));
      return;
    }
    final Matcher appResources = APPLICATION_RESOURCES.matcher(path);
    if (appResources.matches()) {
      resourcesOf(exchange, method, appResources.group(1), body);
      return;
    }
    if ("/api/v1/roles".equals(path)) {
      collection(exchange, method, query, roles, body, true);
      return;
    }
    if ("/api/v1/profiles".equals(path)) {
      collection(exchange, method, query, profiles, body, false);
      return;
    }
    final Matcher roleResources = ROLE_RESOURCES.matcher(path);
    if ("POST".equals(method) && roleResources.matches()) {
      link(exchange, roles, roleResources.group(1), "resourceIds", body.get("resourceId"));
      return;
    }
    final Matcher profileRoles = PROFILE_ROLES.matcher(path);
    if ("POST".equals(method) && profileRoles.matches()) {
      link(exchange, profiles, profileRoles.group(1), "roleIds", body.get("roleId"));
      return;
    }
    send(exchange, 404, Map.of("detail", "ruta desconocida"));
  }

  private void resourcesOf(
      final HttpExchange exchange,
      final String method,
      final String applicationId,
      final Map<String, Object> body)
      throws IOException {
    if ("GET".equals(method)) {
      send(exchange, 200, envelope(resources(applicationId)));
      return;
    }
    final String path = String.valueOf(body.get("path"));
    final String verb = String.valueOf(body.get("method"));
    if (path.contains("{") || path.contains("}")) {
      send(exchange, 500, Map.of("detail", "error interno"));
      return;
    }
    if (!RESOURCE_PATH_CHARACTERS.matcher(path).matches() || !METHODS.contains(verb)) {
      send(exchange, 400, Map.of("detail", "recurso invalido"));
      return;
    }
    final boolean duplicate =
        resources(applicationId).stream()
            .anyMatch(r -> path.equals(r.get("path")) && verb.equals(r.get("method")));
    if (duplicate) {
      send(exchange, 409, Map.of("detail", "recurso duplicado"));
      return;
    }
    final Map<String, Object> created = seedResource(applicationId, verb, path);
    send(exchange, 201, envelope(created));
  }

  private void collection(
      final HttpExchange exchange,
      final String method,
      final String query,
      final List<Map<String, Object>> store,
      final Map<String, Object> body,
      final boolean role)
      throws IOException {
    if ("GET".equals(method)) {
      send(exchange, 200, envelope(page(store, query)));
      return;
    }
    final String name = String.valueOf(body.get("name"));
    final String applicationId = String.valueOf(body.get("applicationId"));
    if (name.length() < 3 || name.length() > 60 || !"APPLICATION".equals(body.get("scope"))) {
      send(exchange, 400, Map.of("detail", "datos invalidos"));
      return;
    }
    final boolean duplicate =
        store.stream()
            .anyMatch(
                r -> name.equals(r.get("name")) && applicationId.equals(r.get("applicationId")));
    if (duplicate) {
      send(exchange, 409, Map.of("detail", "nombre duplicado"));
      return;
    }
    final Map<String, Object> created = new LinkedHashMap<>();
    created.put("id", UUID.randomUUID().toString());
    created.put("name", name);
    created.put("scope", "APPLICATION");
    created.put("applicationId", applicationId);
    created.put(role ? "resourceIds" : "roleIds", new ArrayList<String>());
    store.add(created);
    send(exchange, 201, envelope(created));
  }

  @SuppressWarnings("unchecked")
  private void link(
      final HttpExchange exchange,
      final List<Map<String, Object>> store,
      final String id,
      final String listKey,
      final Object member)
      throws IOException {
    final Map<String, Object> target =
        store.stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
    if (target == null || member == null) {
      send(exchange, 404, Map.of("detail", "no existe"));
      return;
    }
    final List<String> members = (List<String>) target.get(listKey);
    if (members.contains(String.valueOf(member))) {
      send(exchange, 409, Map.of("detail", "vinculo duplicado"));
      return;
    }
    members.add(String.valueOf(member));
    send(exchange, 200, envelope(target));
  }

  private static Map<String, Object> page(
      final List<Map<String, Object>> items, final String query) {
    int offset = 0;
    int limit = 20;
    if (query != null) {
      for (final String pair : query.split("&")) {
        final String[] parts = pair.split("=", 2);
        if (parts.length == 2 && "offset".equals(parts[0])) {
          offset = Integer.parseInt(parts[1]);
        }
        if (parts.length == 2 && "limit".equals(parts[0])) {
          limit = Math.min(Integer.parseInt(parts[1]), 100);
        }
      }
    }
    final int from = Math.min(offset, items.size());
    final int to = Math.min(from + limit, items.size());
    final Map<String, Object> page = new LinkedHashMap<>();
    page.put("content", new ArrayList<>(items.subList(from, to)));
    page.put("total", items.size());
    page.put("page", 0);
    page.put("offset", offset);
    page.put("limit", limit);
    return page;
  }

  private static Map<String, Object> envelope(final Object data) {
    final Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("code", "OK");
    envelope.put("message", "ok");
    envelope.put("data", data);
    return envelope;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readBody(final HttpExchange exchange) throws IOException {
    final byte[] raw = exchange.getRequestBody().readAllBytes();
    if (raw.length == 0) {
      return Map.of();
    }
    return MAPPER.readValue(new String(raw, StandardCharsets.UTF_8), Map.class);
  }

  private static void send(final HttpExchange exchange, final int status, final Object payload)
      throws IOException {
    final byte[] bytes = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
