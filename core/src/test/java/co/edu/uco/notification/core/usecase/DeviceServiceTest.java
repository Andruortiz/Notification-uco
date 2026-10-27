package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Device;
import co.edu.uco.notification.core.domain.RecipientIdentity;
import co.edu.uco.notification.core.domain.policy.TokenMask;
import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.DevicePlatform;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.DeviceNotFoundException;
import co.edu.uco.notification.core.exception.DeviceSubjectForbiddenException;
import co.edu.uco.notification.core.port.out.DeviceRegistryPort;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DeviceServiceTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final AuthenticatedPrincipal CLIENT =
      new AuthenticatedPrincipal("app-1", TENANT, Role.CLIENTE);
  private static final AuthenticatedPrincipal APP_ADMIN =
      new AuthenticatedPrincipal("app-admin-1", TENANT, Role.ADMINISTRADOR_APLICACION);
  private static final AuthenticatedPrincipal ADMIN =
      new AuthenticatedPrincipal("admin-1", TENANT, Role.ADMINISTRADOR_PLATAFORMA);

  private static final RecipientIdentity APP_IDENTITY = identity("app-1");
  private static final RecipientIdentity BOB_IDENTITY = identity("bob");

  private final DeviceRegistryPort registry = mock(DeviceRegistryPort.class);
  private DeviceService service;

  @BeforeEach
  void setUp() {
    service = new DeviceService(registry);
  }

  private static RecipientIdentity identity(final String subject) {
    return new RecipientIdentity("identity-" + subject, TENANT, subject, Instant.now());
  }

  private static Device device(final String subject) {
    return new Device(
        "device-1",
        TENANT,
        "identity-" + subject,
        DevicePlatform.ANDROID,
        "token-abcdef",
        "app-1",
        Instant.now(),
        Instant.now());
  }

  @Test
  void aClientRegistersOnItsOwnIdentity() {
    when(registry.findOrCreateIdentity(TENANT, "app-1")).thenReturn(Mono.just(APP_IDENTITY));
    when(registry.save(any(), any(), any(), any(), any()))
        .thenReturn(Mono.just(new DeviceRegistryPort.Saved(device("app-1"), true)));

    StepVerifier.create(service.register(CLIENT, DevicePlatform.ANDROID, "token-abcdef", null))
        .assertNext(registration -> assertTrue(registration.created()))
        .verifyComplete();

    verify(registry).save(TENANT, APP_IDENTITY, DevicePlatform.ANDROID, "token-abcdef", "app-1");
  }

  @Test
  void aClientCannotRegisterOnAnotherSubject() {
    StepVerifier.create(
            service.register(CLIENT, DevicePlatform.ANDROID, "token-abcdef", "someone-else"))
        .expectError(DeviceSubjectForbiddenException.class)
        .verify();

    verify(registry, never()).findOrCreateIdentity(any(), any());
  }

  @Test
  void anAdministratorRegistersOnAnotherSubjectAndKeepsItselfAsOrigin() {
    when(registry.findOrCreateIdentity(TENANT, "bob")).thenReturn(Mono.just(BOB_IDENTITY));
    when(registry.save(any(), any(), any(), any(), any()))
        .thenReturn(Mono.just(new DeviceRegistryPort.Saved(device("bob"), false)));

    StepVerifier.create(service.register(ADMIN, DevicePlatform.WEB, "token-abcdef", "bob"))
        .assertNext(registration -> assertFalse(registration.created()))
        .verifyComplete();

    verify(registry).save(TENANT, BOB_IDENTITY, DevicePlatform.WEB, "token-abcdef", "admin-1");
  }

  @Test
  void invalidTokensAreRejectedBeforeTouchingTheRegistry() {
    StepVerifier.create(service.register(CLIENT, DevicePlatform.IOS, " ", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(service.register(CLIENT, DevicePlatform.IOS, "x".repeat(4097), null))
        .expectError(IllegalArgumentException.class)
        .verify();

    verify(registry, never()).findOrCreateIdentity(any(), any());
  }

  @Test
  void listingUsesTheSessionSubjectUnlessAdministrator() {
    when(registry.findBySubject(TENANT, "app-1")).thenReturn(Flux.just(device("app-1")));
    when(registry.findBySubject(TENANT, "bob")).thenReturn(Flux.just(device("bob")));

    StepVerifier.create(service.list(CLIENT, null)).expectNextCount(1).verifyComplete();
    StepVerifier.create(service.list(CLIENT, "bob"))
        .expectError(DeviceSubjectForbiddenException.class)
        .verify();
    StepVerifier.create(service.list(ADMIN, "bob")).expectNextCount(1).verifyComplete();
  }

  @Test
  void removalScopesNonAdministratorsToTheirSubjectAndAnswersNotFoundUniformly() {
    when(registry.remove(TENANT, "device-1", "app-1")).thenReturn(Mono.just(false));
    when(registry.remove(TENANT, "device-1", null)).thenReturn(Mono.just(true));

    StepVerifier.create(service.remove(CLIENT, "device-1"))
        .expectError(DeviceNotFoundException.class)
        .verify();
    StepVerifier.create(service.remove(ADMIN, "device-1")).verifyComplete();
    verify(registry).remove(eq(TENANT), eq("device-1"), isNull());
  }

  @Test
  void anAdminOfTheApplicationNeitherErasesNorActsOnAnotherSubject() {
    when(registry.eraseBySubject(TENANT, "bob")).thenReturn(Mono.empty());

    StepVerifier.create(service.erase(APP_ADMIN, "bob"))
        .expectError(DeviceSubjectForbiddenException.class)
        .verify();
    StepVerifier.create(service.list(APP_ADMIN, "bob"))
        .expectError(DeviceSubjectForbiddenException.class)
        .verify();
    StepVerifier.create(service.register(APP_ADMIN, DevicePlatform.WEB, "token-abcdef", "bob"))
        .expectError(DeviceSubjectForbiddenException.class)
        .verify();
    verify(registry, never()).eraseBySubject(TENANT, "bob");
  }

  @Test
  void erasureIsPlatformAdministratorOnly() {
    when(registry.eraseBySubject(TENANT, "bob")).thenReturn(Mono.empty());

    StepVerifier.create(service.erase(CLIENT, "bob"))
        .expectError(DeviceSubjectForbiddenException.class)
        .verify();
    StepVerifier.create(service.erase(ADMIN, "bob")).verifyComplete();
    assertThrows(NullPointerException.class, () -> new DeviceService(null));
  }

  @Test
  void theMaskKeepsOnlyTheLastFourCharacters() {
    assertEquals("****cdef", TokenMask.mask("token-abcdef"));
    assertEquals("****", TokenMask.mask("abc"));
    assertEquals("****", TokenMask.mask(null));
  }
}
