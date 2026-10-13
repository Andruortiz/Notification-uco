package co.edu.uco.notification.infrastructure.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.support.DashboardQueries;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ComposeObservabilityContractTest {

  private static final String BASE =
      DashboardQueries.read(DashboardQueries.repositoryFile("docker-compose.yml"));
  private static final String EXTRA =
      DashboardQueries.read(DashboardQueries.repositoryFile("docker-compose.observability.yml"));

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> services(final String yaml) {
    final Map<String, Object> root = new Yaml().load(yaml);
    return (Map<String, Map<String, Object>>) root.get("services");
  }

  @Test
  void observabilityServicesLiveOnlyInTheAdditionalFile() {
    assertEquals(java.util.Set.of("prometheus", "grafana"), services(EXTRA).keySet());
    assertFalse(services(BASE).containsKey("prometheus"));
    assertFalse(services(BASE).containsKey("grafana"));
  }

  @Test
  void theBaseFileDoesNotRequireGrafanaCredentials() {
    assertFalse(BASE.contains("GRAFANA"));
  }

  @Test
  void imagesCarryAFixedTagNeverLatest() {
    services(EXTRA)
        .forEach(
            (name, service) -> {
              final String image = (String) service.get("image");
              assertTrue(image.contains(":"), name + " needs a tag");
              assertNotEquals("latest", image.substring(image.indexOf(':') + 1));
            });
    assertEquals("prom/prometheus:v3.5.1", services(EXTRA).get("prometheus").get("image"));
    assertEquals("grafana/grafana:12.2.0", services(EXTRA).get("grafana").get("image"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void grafanaCredentialsAreRequiredFromTheEnvironmentWithoutDefaults() {
    final Map<String, Object> environment =
        (Map<String, Object>) services(EXTRA).get("grafana").get("environment");

    for (final String key : List.of("GF_SECURITY_ADMIN_USER", "GF_SECURITY_ADMIN_PASSWORD")) {
      final String value = String.valueOf(environment.get(key));
      assertTrue(value.startsWith("${GRAFANA_ADMIN_"), key);
      assertTrue(value.contains(":?"), key + " must fail when unset");
      assertFalse(value.contains(":-"), key + " must have no default");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void publishedPortsListenOnLocalhostAndNeverExposeTheManagementPort() {
    services(EXTRA)
        .values()
        .forEach(
            service ->
                ((List<String>) service.get("ports"))
                    .forEach(
                        port -> {
                          assertTrue(port.startsWith("127.0.0.1:"), port);
                          assertFalse(port.contains("8061"), port);
                        }));
    services(BASE)
        .values()
        .forEach(
            service -> {
              final List<String> ports = (List<String>) service.getOrDefault("ports", List.of());
              ports.forEach(port -> assertFalse(port.contains("8061"), port));
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void prometheusKeepsThirtyOneDaysOnANamedVolume() {
    final List<String> command = (List<String>) services(EXTRA).get("prometheus").get("command");
    assertTrue(command.contains("--storage.tsdb.retention.time=31d"));
    final List<String> volumes = (List<String>) services(EXTRA).get("prometheus").get("volumes");
    assertTrue(volumes.contains("prometheus-data:/prometheus"));
    final Map<String, Object> declared = new Yaml().load(EXTRA);
    assertTrue(((Map<String, Object>) declared.get("volumes")).containsKey("prometheus-data"));
    assertTrue(((Map<String, Object>) declared.get("volumes")).containsKey("grafana-data"));
  }
}
