package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.adapter.in.web.PublicRoutes;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PublicRoutesConsistencyTest {

  private static final Pattern DEFAULT = Pattern.compile("^\\$\\{[A-Z_]+:(.*)}$");
  private static final List<String> MANAGEMENT =
      List.of("/actuator/health", "/actuator/health/**", "/actuator/prometheus");

  private static List<String> contractPaths(final String exposure) {
    final List<String> paths = new ArrayList<>();
    for (final Object entry : ApiContract.list(ApiContract.document().get("x-public-routes"))) {
      final Map<String, Object> route = ApiContract.map(entry);
      if (exposure == null || exposure.equals(route.get("exposure"))) {
        paths.add((String) route.get("path"));
      }
    }
    return paths;
  }

  private static Map<String, Object> yaml(final String resource) throws IOException {
    try (InputStream input =
        PublicRoutesConsistencyTest.class.getClassLoader().getResourceAsStream(resource)) {
      return new Yaml().load(input);
    }
  }

  private static Object at(final Map<String, Object> root, final String... keys) {
    Object current = root;
    for (final String key : keys) {
      current = ApiContract.map(current).get(key);
    }
    return current;
  }

  private static List<String> defaultOf(final Object placeholder) {
    final Matcher matcher = DEFAULT.matcher(String.valueOf(placeholder));
    assertTrue(matcher.matches(), String.valueOf(placeholder));
    return Arrays.stream(matcher.group(1).split(",")).map(String::trim).toList();
  }

  private static List<String> publicPathsOf(final String resource) throws IOException {
    return defaultOf(at(yaml(resource), "security", "pep", "enforcement", "public-paths"));
  }

  @Test
  void theDefaultPublicPathsAreTheAlwaysPublicContractRoutesPlusTheManagementProbes()
      throws IOException {
    final List<String> expected = new ArrayList<>(MANAGEMENT);
    expected.addAll(contractPaths("always"));

    assertEquals(expected, publicPathsOf("application.yml"));
  }

  @Test
  void theLocalProfileAddsExactlyTheNonProductionRoutes() throws IOException {
    final List<String> expected = new ArrayList<>(publicPathsOf("application.yml"));
    expected.addAll(contractPaths("non-production"));

    assertEquals(expected, publicPathsOf("application-local.yml"));
  }

  @Test
  void theServiceFilterExemptsEveryContractRouteAndNothingElseOfTheContract() {
    final PublicRoutes routes = PublicRoutes.withDocumentationPaths();

    assertEquals(
        contractPaths(null).stream().sorted().toList(),
        PublicRoutes.DOCUMENTATION.stream().sorted().toList());
    for (final String path : contractPaths(null)) {
      assertTrue(routes.matches(path.replace("**", "index.html")), path);
    }
    for (final ApiContract.Operation operation : ApiContract.operations()) {
      assertFalse(routes.matches(operation.path()), operation.key());
    }
  }

  @Test
  void theSwaggerInterfaceIsDisabledByDefaultAndEnabledInTheLocalProfile() throws IOException {
    assertEquals(
        "${SWAGGER_UI_ENABLED:false}",
        at(yaml("application.yml"), "springdoc", "swagger-ui", "enabled"));
    assertEquals(
        "${SWAGGER_UI_ENABLED:true}",
        at(yaml("application-local.yml"), "springdoc", "swagger-ui", "enabled"));
  }

  @Test
  void publicRoutesAreNotProtectedResourcesOfThePdpCatalog() {
    final List<String> operationPaths =
        ApiContract.operations().stream().map(ApiContract.Operation::path).toList();

    for (final String path : contractPaths(null)) {
      assertFalse(operationPaths.contains(path), path);
      assertFalse(ApiContract.paths().containsKey(path), path);
    }
    assertEquals(16, ApiContract.operations().size());
  }

  @Test
  void everyPublicRouteIsAReadOnlyGetWithoutSecurityRequirements() {
    for (final Object entry : ApiContract.list(ApiContract.document().get("x-public-routes"))) {
      final Map<String, Object> route = ApiContract.map(entry);
      assertEquals("GET", route.get("method"), String.valueOf(route));
      assertEquals(List.of(), route.get("security"), String.valueOf(route));
    }
  }
}
