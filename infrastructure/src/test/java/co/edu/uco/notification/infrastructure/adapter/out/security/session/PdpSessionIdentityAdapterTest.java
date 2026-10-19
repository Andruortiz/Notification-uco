package co.edu.uco.notification.infrastructure.adapter.out.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.SessionCredential;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AuthenticationUnavailableException;
import co.edu.uco.notification.core.exception.InvalidTokenException;
import co.edu.uco.notification.infrastructure.support.StubSecurityPlatform;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class PdpSessionIdentityAdapterTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(1);

  private static StubSecurityPlatform platform;
  private static PdpSessionIdentityAdapter adapter;

  @BeforeAll
  static void start() {
    platform = new StubSecurityPlatform();
    adapter =
        new PdpSessionIdentityAdapter(
            WebClient.builder().baseUrl(platform.baseUrl()).build(), "/api/v1/session", TIMEOUT);
  }

  @AfterAll
  static void stop() {
    platform.close();
  }

  @BeforeEach
  void reset() {
    platform.reset();
    platform.withSession("cookie-a", "universidad-uco").withSession("cookie-b", "tenant-b");
  }

  @Test
  void aValidSessionBecomesAClientePrincipalWithTheTenantOfTheSession() {
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
        .expectNext(
            new AuthenticatedPrincipal(
                "user-cookie-a", TenantId.of("universidad-uco"), Role.CLIENTE))
        .verifyComplete();
  }

  @Test
  void twoSessionsOfDifferentTenantsResolveToTheirOwnTenant() {
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-b")))
        .assertNext(principal -> assertEquals(TenantId.of("tenant-b"), principal.tenantId()))
        .verifyComplete();
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
        .assertNext(principal -> assertEquals(TenantId.of("universidad-uco"), principal.tenantId()))
        .verifyComplete();
  }

  @Test
  void onlyTheSessionCookieIsForwardedToThePlatform() {
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
        .expectNextCount(1)
        .verifyComplete();

    assertEquals(java.util.List.of("cookie-a"), platform.sessionCookiesReceived());
    assertEquals(1, platform.sessionCalls());
  }

  @Test
  void anUnknownRejectedOrRevokedSessionIsInvalid() {
    StepVerifier.create(adapter.resolve(new SessionCredential("never-issued")))
        .expectErrorSatisfies(
            error -> {
              assertTrue(error instanceof InvalidTokenException);
              assertEquals(
                  InvalidTokenException.Reason.INVALID_SESSION,
                  ((InvalidTokenException) error).reason());
            })
        .verify();

    platform.revoke("cookie-a");
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
        .expectError(InvalidTokenException.class)
        .verify();

    platform.failSessionWith(403);
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-b")))
        .expectError(InvalidTokenException.class)
        .verify();
  }

  @Test
  void aSessionWithoutSubjectOrTenantOrWithUnreadableBodyIsInvalid() {
    for (final String body :
        new String[] {
          "{\"code\":\"SESSION_ACTIVE\",\"data\":{\"subject\":\"u-1\"}}",
          "{\"code\":\"SESSION_ACTIVE\",\"data\":{\"tenantId\":\"t\"}}",
          "{\"code\":\"SESSION_ACTIVE\",\"data\":{\"subject\":\" \",\"tenantId\":\"t\"}}",
          "{\"code\":\"SESSION_ACTIVE\"}",
          "not-json"
        }) {
      platform.answerSessionWith(body);

      StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
          .expectError(InvalidTokenException.class)
          .verify();
    }
  }

  @Test
  void unknownFieldsOfTheSessionAreIgnored() {
    platform.answerSessionWith(
        "{\"code\":\"SESSION_ACTIVE\",\"extra\":1,\"data\":{\"subject\":\"u-9\","
            + "\"tenantId\":\"t-9\",\"name\":\"\",\"email\":\"e\",\"roles\":[\"X\"]}}");

    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
        .assertNext(
            principal -> {
              assertEquals("u-9", principal.subject());
              assertEquals(Role.CLIENTE, principal.role());
            })
        .verifyComplete();
  }

  @Test
  void aServerErrorOrASlowPlatformIsUnavailableWithinTheBound() {
    platform.failSessionWith(500);
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
        .expectError(AuthenticationUnavailableException.class)
        .verify(Duration.ofSeconds(5));

    platform.reset();
    platform.withSession("cookie-a", "universidad-uco");
    platform.delaySessionBy(Duration.ofSeconds(4));
    final Instant start = Instant.now();
    StepVerifier.create(adapter.resolve(new SessionCredential("cookie-a")))
        .expectError(AuthenticationUnavailableException.class)
        .verify(Duration.ofSeconds(5));
    assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(3)) < 0);
  }

  @Test
  void aPlatformThatIsDownIsUnavailable() {
    final PdpSessionIdentityAdapter unreachable =
        new PdpSessionIdentityAdapter(
            WebClient.builder().baseUrl("http://127.0.0.1:1").build(), "/api/v1/session", TIMEOUT);

    StepVerifier.create(unreachable.resolve(new SessionCredential("cookie-a")))
        .expectError(AuthenticationUnavailableException.class)
        .verify(Duration.ofSeconds(5));
  }
}
