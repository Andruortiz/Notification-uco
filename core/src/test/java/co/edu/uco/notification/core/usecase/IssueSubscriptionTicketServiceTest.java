package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.SubscriptionTicketFingerprint;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.IssuedSubscriptionTicket;
import co.edu.uco.notification.core.port.out.SubscriptionTicketPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IssueSubscriptionTicketServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final AuthenticatedPrincipal PRINCIPAL =
      new AuthenticatedPrincipal("client-1", TenantId.of("tenant-a"), Role.CLIENTE);

  private record Saved(String fingerprint, AuthenticatedPrincipal principal, Instant expiresAt) {}

  private static final class RecordingPort implements SubscriptionTicketPort {

    private final List<Saved> saved = new ArrayList<>();

    @Override
    public Mono<Void> save(
        final String fingerprint, final AuthenticatedPrincipal principal, final Instant expiresAt) {
      saved.add(new Saved(fingerprint, principal, expiresAt));
      return Mono.empty();
    }

    @Override
    public Mono<AuthenticatedPrincipal> consume(final String fingerprint) {
      return saved.stream()
          .filter(entry -> entry.fingerprint().equals(fingerprint))
          .findFirst()
          .map(entry -> Mono.just(entry.principal()))
          .orElse(Mono.empty());
    }
  }

  private final RecordingPort port = new RecordingPort();
  private final IssueSubscriptionTicketService service =
      new IssueSubscriptionTicketService(
          port, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

  @Test
  void issuesAnOpaqueBase64UrlTicketOf43CharactersValidFor30Seconds() {
    StepVerifier.create(service.issue(PRINCIPAL))
        .assertNext(
            issued -> {
              assertEquals(43, issued.ticket().length());
              assertTrue(issued.ticket().matches("[A-Za-z0-9_-]{43}"));
              assertEquals(30L, issued.expiresInSeconds());
            })
        .verifyComplete();

    assertEquals(1, port.saved.size());
    assertEquals(NOW.plusSeconds(30), port.saved.get(0).expiresAt());
    assertEquals(PRINCIPAL, port.saved.get(0).principal());
  }

  @Test
  void storesOnlyTheSha256FingerprintNeverTheTicketInClear() {
    final IssuedSubscriptionTicket issued = service.issue(PRINCIPAL).block();

    final Saved saved = port.saved.get(0);
    assertEquals(SubscriptionTicketFingerprint.of(issued.ticket()), saved.fingerprint());
    assertNotEquals(issued.ticket(), saved.fingerprint());
    assertFalse(saved.fingerprint().contains(issued.ticket()));
  }

  @Test
  void twoIssuancesProduceDifferentTickets() {
    final String first = service.issue(PRINCIPAL).block().ticket();
    final String second = service.issue(PRINCIPAL).block().ticket();

    assertNotEquals(first, second);
    assertNotEquals(port.saved.get(0).fingerprint(), port.saved.get(1).fingerprint());
  }

  @Test
  void theClearTicketReturnedToTheCallerResolvesThePrincipalThroughItsFingerprint() {
    final IssuedSubscriptionTicket issued = service.issue(PRINCIPAL).block();

    StepVerifier.create(port.consume(SubscriptionTicketFingerprint.of(issued.ticket())))
        .expectNext(PRINCIPAL)
        .verifyComplete();
    StepVerifier.create(port.consume(SubscriptionTicketFingerprint.of("other-ticket")))
        .verifyComplete();
  }
}
