package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProtectedResourcesCatalogTest {

  private static final List<String> ROLES = ProtectedResourcesCatalog.ROLES;

  private static List<String> profilesFor(final String minimumRole) {
    return ProtectedResourcesCatalog.profilesFor(minimumRole);
  }

  private static List<Map<String, Object>> catalog() {
    return ProtectedResourcesCatalog.entries();
  }

  private static long countForProfile(final List<Map<String, Object>> entries, final String role) {
    return entries.stream()
        .filter(entry -> ((List<?>) entry.get("profiles")).contains(role))
        .count();
  }

  @Test
  void theCatalogHasOneEntryPerOperationWithoutDuplicates() {
    final List<Map<String, Object>> entries = catalog();

    assertEquals(16, entries.size());
    assertEquals(
        16,
        entries.stream()
            .map(entry -> entry.get("method") + " " + entry.get("path"))
            .collect(Collectors.toSet())
            .size());
    assertEquals(
        11, entries.stream().filter(entry -> "available".equals(entry.get("status"))).count());
  }

  @Test
  void everyEntryDeclaresARegistrablePathARoleAndAnOperationId() {
    for (final Map<String, Object> entry : catalog()) {
      assertTrue(((String) entry.get("path")).matches(ApiContract.PATH_REGEX), entry.toString());
      assertTrue(ROLES.contains(entry.get("minimumRole")), entry.toString());
      assertTrue(Set.of("GET", "POST", "PUT").contains(entry.get("method")), entry.toString());
      assertTrue(entry.get("operationId") != null, entry.toString());
    }
  }

  @Test
  void profilesAreCumulativeAndComputedFromTheMinimumRole() {
    final List<Map<String, Object>> entries = catalog();
    final long clientes =
        entries.stream().filter(entry -> "CLIENTE".equals(entry.get("minimumRole"))).count();
    final long operadores =
        entries.stream().filter(entry -> "OPERADOR".equals(entry.get("minimumRole"))).count();

    assertEquals(clientes, countForProfile(entries, "CLIENTE"));
    assertEquals(clientes + operadores, countForProfile(entries, "OPERADOR"));
    assertEquals(16, countForProfile(entries, "ADMINISTRADOR"));
    assertEquals(List.of("OPERADOR", "ADMINISTRADOR"), profilesFor("OPERADOR"));
  }

  @Test
  void theCatalogIsWrittenAsABuildArtifactForThePdpLoader() throws IOException {
    final Path target = Path.of("target", "contract", "protected-resources.json");
    ProtectedResourcesCatalog.write(target, catalog());

    final List<?> written = new ObjectMapper().readValue(target.toFile(), List.class);
    assertEquals(16, written.size());
  }
}
