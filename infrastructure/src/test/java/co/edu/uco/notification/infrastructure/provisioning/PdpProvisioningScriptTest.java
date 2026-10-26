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
  private static final String SESSION_B = "sesion-tenant-b-0123456789abcdef";
  private static final String TENANT_A = "tenant-a";
  private static final String TENANT_B = "tenant-b";
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
    pdp.addSession(SESSION_B, TENANT_B);
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
    return runWith(withSession ? SESSION : null, extra);
  }

  private Result runWith(final String session, final String... extra)
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
    if (session != null) {
      builder.environment().put("PDP_ADMIN_SESSION", session);
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

  private List<Map<String, Object>> profilesOf(final String tenant) {
    return pdp.profiles(FakePdpAdmin.APPLICATION_ID).stream()
        .filter(profile -> tenant.equals(profile.get("tenantId")))
        .toList();
  }

  private static Set<String> keysAllowing(final String role) {
    return keysOf(
        available().stream().filter(operation -> operation.allowedRoles().contains(role)).toList());
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
  void firstRunCreatesTheSixRolesWithExactlyTheOperationsThatListThem()
      throws IOException, InterruptedException {
    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    final List<Map<String, Object>> roles = pdp.roles(FakePdpAdmin.APPLICATION_ID);
    assertEquals(
        Set.copyOf(ProtectedResourcesCatalog.ROLES),
        roles.stream().map(role -> (String) role.get("name")).collect(Collectors.toSet()));
    for (final String roleName : ProtectedResourcesCatalog.ROLES) {
      assertEquals(keysAllowing(roleName), grantedKeys(byName(roles, roleName)), roleName);
    }
  }

  @Test
  void eachRoleHasOneProfileWithOnlyThatRole() throws IOException, InterruptedException {
    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    final List<Map<String, Object>> roles = pdp.roles(FakePdpAdmin.APPLICATION_ID);
    final List<Map<String, Object>> profiles = profilesOf(TENANT_A);
    assertEquals(
        ProtectedResourcesCatalog.ROLES.stream()
            .map(role -> "PERFIL_" + role)
            .collect(Collectors.toSet()),
        profiles.stream().map(p -> (String) p.get("name")).collect(Collectors.toSet()));
    for (final String role : ProtectedResourcesCatalog.ROLES) {
      final Map<String, Object> profile = byName(profiles, "PERFIL_" + role);
      assertEquals(
          Set.of((String) byName(roles, role).get("id")),
          Set.copyOf(profileRoleIds(profile)),
          profile.get("name").toString());
    }
  }

  @Test
  void aUserWithTwoProfilesGetsTheUnionOfTheirOperations()
      throws IOException, InterruptedException {
    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    final List<Map<String, Object>> roles = pdp.roles(FakePdpAdmin.APPLICATION_ID);
    final Set<String> union = new LinkedHashSet<>();
    union.addAll(grantedKeys(byName(roles, "EDITOR_PLANTILLAS")));
    union.addAll(grantedKeys(byName(roles, "OPERADOR")));
    final Set<String> expected = new LinkedHashSet<>(keysAllowing("EDITOR_PLANTILLAS"));
    expected.addAll(keysAllowing("OPERADOR"));
    assertEquals(expected, union);
  }

  @SuppressWarnings("unchecked")
  private static List<String> profileRoleIds(final Map<String, Object> profile) {
    return (List<String>) profile.get("roleIds");
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
  void aSecondTenantGetsItsOwnProfilesWithoutTouchingTheFirst()
      throws IOException, InterruptedException {
    assertEquals(0, run(true).exitCode());
    final List<Map<String, Object>> profilesA = profilesOf(TENANT_A);
    final int roles = pdp.roles(FakePdpAdmin.APPLICATION_ID).size();
    final int resources = pdp.resources(FakePdpAdmin.APPLICATION_ID).size();
    pdp.clearRequests();

    final Result second = runWith(SESSION_B);

    assertEquals(0, second.exitCode(), second.output());
    assertEquals(ProtectedResourcesCatalog.ROLES.size(), profilesOf(TENANT_B).size());
    assertEquals(profilesA, profilesOf(TENANT_A));
    assertEquals(roles, pdp.roles(FakePdpAdmin.APPLICATION_ID).size());
    assertEquals(resources, pdp.resources(FakePdpAdmin.APPLICATION_ID).size());
    assertTrue(
        pdp.mutations().stream().allMatch(m -> m.startsWith("POST /api/v1/profiles")),
        pdp.mutations().toString());
    assertTrue(second.output().contains("Tenant de la sesion: " + TENANT_B), second.output());
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
  void anAccumulativeSchemaFromBeforeIsReportedAndKept() throws IOException, InterruptedException {
    final Map<String, Object> cliente = pdp.seedRole(FakePdpAdmin.APPLICATION_ID, "CLIENTE");
    final Map<String, Object> operador = pdp.seedRole(FakePdpAdmin.APPLICATION_ID, "OPERADOR");
    final Map<String, Object> administrador =
        pdp.seedRole(FakePdpAdmin.APPLICATION_ID, "ADMINISTRADOR");
    pdp.seedProfile(
        FakePdpAdmin.APPLICATION_ID,
        TENANT_A,
        "PERFIL_OPERADOR",
        List.of((String) cliente.get("id"), (String) operador.get("id")));
    pdp.seedProfile(
        FakePdpAdmin.APPLICATION_ID,
        TENANT_A,
        "PERFIL_ADMINISTRADOR",
        List.of(
            (String) cliente.get("id"),
            (String) operador.get("id"),
            (String) administrador.get("id")));

    final Result result = run(true);

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("[DIVERGENCIA] rol ADMINISTRADOR"), result.output());
    assertTrue(
        result.output().contains("[DIVERGENCIA] perfil PERFIL_OPERADOR tiene el rol CLIENTE"),
        result.output());
    assertTrue(
        result.output().contains("[DIVERGENCIA] perfil PERFIL_ADMINISTRADOR"), result.output());
    assertTrue(
        pdp.roles(FakePdpAdmin.APPLICATION_ID).stream()
            .anyMatch(r -> "ADMINISTRADOR".equals(r.get("name"))));
    assertTrue(
        profilesOf(TENANT_A).stream().anyMatch(p -> "PERFIL_ADMINISTRADOR".equals(p.get("name"))));
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
    assertEquals(
        ProtectedResourcesCatalog.ROLES.size(), pdp.roles(FakePdpAdmin.APPLICATION_ID).size());
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
    for (final String role : ProtectedResourcesCatalog.ROLES) {
      assertTrue(result.output().contains("perfil PERFIL_" + role), role);
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
        result.output().contains("[VINCULAR] perfil PERFIL_AUDITOR -> rol AUDITOR"),
        result.output());
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
  void aCatalogWithAnInvalidAllowedRolesListIsRejectedBeforeAnyCall()
      throws IOException, InterruptedException {
    final List<List<String>> invalid =
        List.of(List.of("ROL_INVENTADO"), List.of("OPERADOR", "OPERADOR"), List.of());
    for (final List<String> roles : invalid) {
      final List<Map<String, Object>> entries = ProtectedResourcesCatalog.entries();
      final Map<String, Object> tampered = new LinkedHashMap<>(entries.get(0));
      tampered.put("allowedRoles", roles);
      entries.set(0, tampered);
      ProtectedResourcesCatalog.write(catalog, entries);

      final Result result = run(true);

      assertNotEquals(0, result.exitCode(), roles.toString());
      assertTrue(result.output().contains("allowedRoles"), result.output());
      assertTrue(pdp.requests().isEmpty(), roles.toString());
    }
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
