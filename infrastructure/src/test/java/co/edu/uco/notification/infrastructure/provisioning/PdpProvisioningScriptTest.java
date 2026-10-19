package co.edu.uco.notification.infrastructure.provisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.uco.notification.infrastructure.contract.ApiContract;
import co.edu.uco.notification.infrastructure.contract.ProtectedResourcesCatalog;
import co.edu.uco.notification.infrastructure.support.FakePdpAdmin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdpProvisioningScriptTest {

  private static final String SESSION = "sesion-secreta-0123456789abcdef";
  private static final Path SCRIPT = Path.of("..", "scripts", "provision-pdp.ps1").normalize();
  private static final String SHELL = detectShell();

  @TempDir Path temp;

  private FakePdpAdmin pdp;
  private Path catalog;

  private record Result(int exitCode, String output) {}

  private static String detectShell() {
    for (final String candidate : List.of("pwsh", "powershell")) {
      try {
        final Process probe =
            new ProcessBuilder(candidate, "-NoProfile", "-Command", "exit 0")
                .redirectErrorStream(true)
                .start();
        probe.getInputStream().readAllBytes();
        if (probe.waitFor(60, TimeUnit.SECONDS) && probe.exitValue() == 0) {
          return candidate;
        }
      } catch (final IOException e) {
        continue;
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  @BeforeEach
  void startFakePdp() throws IOException {
    if (System.getenv("CI") == null) {
      assumeTrue(SHELL != null, "PowerShell no esta disponible en este equipo");
    } else {
      assertTrue(SHELL != null, "CI debe proveer pwsh");
    }
    pdp = new FakePdpAdmin(SESSION);
    catalog = temp.resolve("protected-resources.json");
    ProtectedResourcesCatalog.write(catalog, ProtectedResourcesCatalog.entries());
  }

  @AfterEach
  void stopFakePdp() {
    if (pdp != null) {
      pdp.close();
    }
  }

  private Result run(final boolean withSession, final String... extra)
      throws IOException, InterruptedException {
    final List<String> command = new ArrayList<>();
    command.add(SHELL);
    command.add("-NoProfile");
    command.add("-NonInteractive");
    if (System.getProperty("os.name").toLowerCase().contains("win")) {
      command.addAll(List.of("-ExecutionPolicy", "Bypass"));
    }
    command.addAll(List.of("-File", SCRIPT.toAbsolutePath().toString()));
    command.addAll(List.of("-PdpUrl", pdp.baseUrl(), "-CatalogPath", catalog.toString()));
    command.addAll(List.of(extra));
    final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().remove("PDP_ADMIN_SESSION");
    builder.environment().remove("PDP_BASE_URL");
    if (withSession) {
      builder.environment().put("PDP_ADMIN_SESSION", SESSION);
    }
    final Process process = builder.start();
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(180, TimeUnit.SECONDS), "el script no termino a tiempo");
    return new Result(process.exitValue(), output);
  }

  private static List<ApiContract.Operation> available() {
    return ApiContract.operations().stream().filter(ApiContract.Operation::available).toList();
  }

  private static Set<String> keysOf(final List<ApiContract.Operation> operations) {
    return operations.stream().map(ApiContract.Operation::key).collect(Collectors.toSet());
  }

  private Set<String> registeredKeys() {
    return pdp.resources(FakePdpAdmin.APPLICATION_ID).stream()
        .map(resource -> resource.get("method") + " " + resource.get("path"))
        .collect(Collectors.toSet());
  }

  private Map<String, String> resourceKeyById() {
    final Map<String, String> keys = new LinkedHashMap<>();
    for (final Map<String, Object> resource : pdp.resources(FakePdpAdmin.APPLICATION_ID)) {
      keys.put((String) resource.get("id"), resource.get("method") + " " + resource.get("path"));
    }
    return keys;
  }

  @SuppressWarnings("unchecked")
  private Set<String> grantedKeys(final Map<String, Object> role) {
    final Map<String, String> keys = resourceKeyById();
    return ((List<String>) role.get("resourceIds"))
        .stream().map(keys::get).collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Map<String, Object> byName(final List<Map<String, Object>> items, final String name) {
    return items.stream()
        .filter(item -> name.equals(item.get("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("falta " + name));
  }

  @Test
  void firstRunRegistersExactlyTheAvailableOperationsOfTheContract()
      throws IOException, InterruptedException {
    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    assertEquals(keysOf(available()), registeredKeys());
    final long planned = ApiContract.operations().stream().filter(o -> !o.available()).count();
    assertTrue(planned > 0);
    for (final ApiContract.Operation operation : ApiContract.operations()) {
      if (!operation.available()) {
        assertFalse(registeredKeys().contains(operation.key()), operation.key());
      }
    }
  }

  @Test
  void rolesGrantOnlyTheirOwnMinimumRoleOperations() throws IOException, InterruptedException {
    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    final List<Map<String, Object>> roles = pdp.roles(FakePdpAdmin.APPLICATION_ID);
    assertEquals(
        Set.copyOf(ProtectedResourcesCatalog.ROLES),
        roles.stream().map(role -> (String) role.get("name")).collect(Collectors.toSet()));
    for (final String roleName : ProtectedResourcesCatalog.ROLES) {
      final Set<String> expected =
          keysOf(
              available().stream()
                  .filter(operation -> roleName.equals(operation.minimumRole()))
                  .toList());
      assertEquals(expected, grantedKeys(byName(roles, roleName)), roleName);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void profilesAreCumulativeOverRoles() throws IOException, InterruptedException {
    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    final List<Map<String, Object>> roles = pdp.roles(FakePdpAdmin.APPLICATION_ID);
    final List<Map<String, Object>> profiles = pdp.profiles(FakePdpAdmin.APPLICATION_ID);
    final Map<String, List<String>> expected =
        Map.of(
            "PERFIL_CLIENTE", List.of("CLIENTE"),
            "PERFIL_OPERADOR", List.of("CLIENTE", "OPERADOR"),
            "PERFIL_ADMINISTRADOR", List.of("CLIENTE", "OPERADOR", "ADMINISTRADOR"));
    assertEquals(
        expected.keySet(),
        profiles.stream().map(p -> (String) p.get("name")).collect(Collectors.toSet()));
    for (final Map.Entry<String, List<String>> entry : expected.entrySet()) {
      final Map<String, Object> profile = byName(profiles, entry.getKey());
      final Set<String> roleIds =
          entry.getValue().stream()
              .map(name -> (String) byName(roles, name).get("id"))
              .collect(Collectors.toSet());
      assertEquals(roleIds, Set.copyOf((List<String>) profile.get("roleIds")), entry.getKey());
    }
  }

  @Test
  void effectiveAccessOfEachProfileMatchesTheCatalogProfiles()
      throws IOException, InterruptedException {
    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    final List<Map<String, Object>> roles = pdp.roles(FakePdpAdmin.APPLICATION_ID);
    for (final String profile : ProtectedResourcesCatalog.ROLES) {
      final Set<String> viaRoles = new LinkedHashSet<>();
      for (final String member :
          ProtectedResourcesCatalog.ROLES.subList(
              0, ProtectedResourcesCatalog.ROLES.indexOf(profile) + 1)) {
        viaRoles.addAll(grantedKeys(byName(roles, member)));
      }
      final Set<String> viaCatalog =
          keysOf(
              available().stream()
                  .filter(
                      op ->
                          ProtectedResourcesCatalog.profilesFor(op.minimumRole()).contains(profile))
                  .toList());
      assertEquals(viaCatalog, viaRoles, profile);
    }
  }

  @Test
  void secondRunMakesNoMutationsAndDuplicatesNothing() throws IOException, InterruptedException {
    assertEquals(0, run(true).exitCode());
    final int resources = pdp.resources(FakePdpAdmin.APPLICATION_ID).size();
    final int roles = pdp.roles(FakePdpAdmin.APPLICATION_ID).size();
    final int profiles = pdp.profiles(FakePdpAdmin.APPLICATION_ID).size();
    pdp.clearRequests();

    final Result second = run(true);

    assertEquals(0, second.exitCode(), second.output());
    assertTrue(pdp.mutations().isEmpty(), pdp.mutations().toString());
    assertEquals(resources, pdp.resources(FakePdpAdmin.APPLICATION_ID).size());
    assertEquals(roles, pdp.roles(FakePdpAdmin.APPLICATION_ID).size());
    assertEquals(profiles, pdp.profiles(FakePdpAdmin.APPLICATION_ID).size());
    assertFalse(second.output().contains("[CREADO]"), second.output());
    assertTrue(second.output().contains("[EXISTE]"), second.output());
  }

  @Test
  void aPartialStateIsCompletedWithoutRecreatingWhatExists()
      throws IOException, InterruptedException {
    final Map<String, Object> existing = pdp.seedRole(FakePdpAdmin.APPLICATION_ID, "CLIENTE");
    final ApiContract.Operation first = available().get(0);
    pdp.seedResource(FakePdpAdmin.APPLICATION_ID, first.method(), first.path());

    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    assertEquals(keysOf(available()), registeredKeys());
    assertEquals(
        1,
        pdp.roles(FakePdpAdmin.APPLICATION_ID).stream()
            .filter(r -> "CLIENTE".equals(r.get("name")))
            .count());
    assertEquals(
        existing.get("id"), byName(pdp.roles(FakePdpAdmin.APPLICATION_ID), "CLIENTE").get("id"));
    assertEquals(
        available().size() - 1L,
        pdp.mutations().stream()
            .filter(m -> m.endsWith("/resources") && m.contains("applications"))
            .count());
  }

  @Test
  void rolesOfOtherApplicationsAreNeverTouchedEvenWithManyOfThem()
      throws IOException, InterruptedException {
    for (int i = 0; i < 130; i++) {
      pdp.seedRole(FakePdpAdmin.OTHER_APPLICATION_ID, "ROL_AJENO_" + i);
    }
    pdp.seedRole(FakePdpAdmin.OTHER_APPLICATION_ID, "CLIENTE");

    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    assertEquals(3, pdp.roles(FakePdpAdmin.APPLICATION_ID).size());
    assertEquals(131, pdp.roles(FakePdpAdmin.OTHER_APPLICATION_ID).size());
    assertTrue(pdp.resources(FakePdpAdmin.OTHER_APPLICATION_ID).isEmpty());
  }

  @Test
  void aResourceOutsideTheCanonicalListIsReportedAndKept()
      throws IOException, InterruptedException {
    pdp.seedResource(FakePdpAdmin.APPLICATION_ID, "GET", "/legacy-endpoint");

    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("[HUERFANO]"), result.output());
    assertTrue(registeredKeys().contains("GET /legacy-endpoint"));
  }

  @Test
  void dryRunWithoutSessionPrintsTheWholePlanAndNeverCallsThePdp()
      throws IOException, InterruptedException {
    final Result result = run(false, "--dry-run");

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(pdp.requests().isEmpty(), pdp.requests().toString());
    for (final ApiContract.Operation operation : available()) {
      assertTrue(result.output().contains("recurso " + operation.key()), operation.key());
    }
    for (final ApiContract.Operation operation : ApiContract.operations()) {
      if (!operation.available()) {
        assertFalse(result.output().contains("recurso " + operation.key()), operation.key());
      }
    }
    for (final String name : List.of("PERFIL_CLIENTE", "PERFIL_OPERADOR", "PERFIL_ADMINISTRADOR")) {
      assertTrue(result.output().contains("perfil " + name), name);
    }
    assertTrue(result.output().contains("manual"), result.output());
  }

  @Test
  void dryRunWithSessionOnlyReadsAndReportsWhatItWouldCreate()
      throws IOException, InterruptedException {
    final Result result = run(true, "-DryRun");

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(pdp.mutations().isEmpty(), pdp.mutations().toString());
    assertTrue(pdp.requests().stream().allMatch(request -> request.startsWith("GET ")));
    assertTrue(pdp.resources(FakePdpAdmin.APPLICATION_ID).isEmpty());
    assertTrue(result.output().contains("[CREAR] recurso"), result.output());
    assertTrue(
        result.output().contains("[VINCULAR] perfil PERFIL_ADMINISTRADOR -> rol ADMINISTRADOR"));
  }

  @Test
  void theSessionValueIsNeverPrintedNorWrittenToTheWorkingDirectory()
      throws IOException, InterruptedException {
    final Result ok = run(true);
    final Result dry = run(true, "--dry-run");
    final Result missingApp = runWithUnknownApplication();

    assertFalse(ok.output().contains(SESSION));
    assertFalse(dry.output().contains(SESSION));
    assertFalse(missingApp.output().contains(SESSION));
    try (var files = Files.walk(temp)) {
      for (final Path file : files.filter(Files::isRegularFile).toList()) {
        assertFalse(Files.readString(file).contains(SESSION), file.toString());
      }
    }
  }

  private Result runWithUnknownApplication() throws IOException, InterruptedException {
    return run(true, "-ApplicationName", "no-existe");
  }

  @Test
  void anInvalidSessionStopsBeforeAnyMutation() throws IOException, InterruptedException {
    pdp.close();
    pdp = new FakePdpAdmin("otra-sesion-distinta");

    final Result result = run(true);

    assertNotEquals(0, result.exitCode());
    assertTrue(pdp.mutations().isEmpty());
    assertTrue(result.output().contains("sesion"), result.output());
    assertFalse(result.output().contains(SESSION));
  }

  @Test
  void anUnknownApplicationStopsWithAClearMessage() throws IOException, InterruptedException {
    final Result result = runWithUnknownApplication();

    assertNotEquals(0, result.exitCode());
    assertTrue(result.output().contains("no esta registrada"), result.output());
    assertTrue(pdp.mutations().isEmpty());
  }

  @Test
  void aCatalogWhoseProfilesContradictTheMinimumRoleIsRejectedBeforeAnyCall()
      throws IOException, InterruptedException {
    final List<Map<String, Object>> entries = ProtectedResourcesCatalog.entries();
    final Map<String, Object> tampered = new LinkedHashMap<>(entries.get(0));
    tampered.put("profiles", List.of("ADMINISTRADOR"));
    entries.set(0, tampered);
    ProtectedResourcesCatalog.write(catalog, entries);

    final Result result = run(true);

    assertNotEquals(0, result.exitCode());
    assertTrue(result.output().contains("perfiles"), result.output());
    assertTrue(pdp.requests().isEmpty());
  }

  @Test
  void aPathThePdpCannotRegisterIsRejectedBeforeAnyCall() throws IOException, InterruptedException {
    for (final String badPath :
        List.of("/notifications/{id}", "/notifications:search", "/notifications/*")) {
      final List<Map<String, Object>> entries = ProtectedResourcesCatalog.entries();
      final Map<String, Object> tampered = new LinkedHashMap<>(entries.get(0));
      tampered.put("path", badPath);
      tampered.put("status", "available");
      entries.set(0, tampered);
      ProtectedResourcesCatalog.write(catalog, entries);

      final Result result = run(true);

      assertNotEquals(0, result.exitCode(), badPath);
      assertTrue(result.output().contains("no registrable"), result.output());
      assertTrue(pdp.requests().isEmpty(), badPath);
    }
  }

  @Test
  void aMissingCatalogExplainsHowToGenerateIt() throws IOException, InterruptedException {
    Files.delete(catalog);

    final Result result = run(true);

    assertEquals(2, result.exitCode());
    assertTrue(result.output().contains("ProtectedResourcesCatalogTest"), result.output());
    assertTrue(pdp.requests().isEmpty());
  }

  @Test
  void theFakePdpRejectsWhatTheRealPdpRejects() {
    assertTrue(
        available().stream()
            .noneMatch(op -> op.path().chars().anyMatch(c -> "{}:*".indexOf(c) >= 0)));
    assertTrue(available().stream().allMatch(op -> op.path().matches(ApiContract.PATH_REGEX)));
  }
}
