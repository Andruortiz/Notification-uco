package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProtectedResourcesCatalogTest {

  private static List<Map<String, Object>> catalog() {
    return ProtectedResourcesCatalog.entries();
  }

  @Test
  void theCatalogHasOneEntryPerOperationWithoutDuplicates() {
    final List<Map<String, Object>> entries = catalog();

    assertEquals(33, entries.size());
    assertEquals(
        33,
        entries.stream()
            .map(entry -> entry.get("method") + " " + entry.get("path"))
            .collect(Collectors.toSet())
            .size());
    assertEquals(
        29, entries.stream().filter(entry -> "available".equals(entry.get("status"))).count());
  }

  @Test
  void everyEntryDeclaresARegistrablePathAllowedRolesAndAnOperationId() {
    for (final Map<String, Object> entry : catalog()) {
      assertTrue(((String) entry.get("path")).matches(ApiContract.PATH_REGEX), entry.toString());
      assertTrue(Set.of("GET", "POST", "PUT").contains(entry.get("method")), entry.toString());
      assertTrue(entry.get("operationId") != null, entry.toString());
      assertFalse(entry.containsKey("minimumRole"), entry.toString());
      assertFalse(entry.containsKey("profiles"), entry.toString());
      final List<?> roles = (List<?>) entry.get("allowedRoles");
      assertFalse(roles.isEmpty(), entry.toString());
      assertTrue(ProtectedResourcesCatalog.ROLES.containsAll(roles), entry.toString());
    }
  }

  @Test
  void theCatalogCarriesTheSixRolesOfTheContract() {
    assertEquals(
        List.of(
            "ADMINISTRADOR_PLATAFORMA",
            "ADMINISTRADOR_APLICACION",
            "EDITOR_PLANTILLAS",
            "OPERADOR",
            "AUDITOR",
            "CLIENTE"),
        ProtectedResourcesCatalog.ROLES);
  }

  @Test
  void theCatalogIsWrittenAsABuildArtifactForThePdpLoader() throws IOException {
    final Path target = Path.of("target", "contract", "protected-resources.json");
    ProtectedResourcesCatalog.write(target, catalog());

    final List<?> written = new ObjectMapper().readValue(target.toFile(), List.class);
    assertEquals(33, written.size());
  }
}
