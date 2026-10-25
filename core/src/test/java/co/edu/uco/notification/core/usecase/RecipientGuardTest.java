package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.UndeliverableRecipient;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.InvalidRecipientException;
import co.edu.uco.notification.core.exception.RecipientUndeliverableException;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.MessageRecipientRef;
import co.edu.uco.notification.core.port.out.NotificationMetricsPort;
import co.edu.uco.notification.core.port.out.ProviderMessageLookupPort;
import co.edu.uco.notification.core.repository.UndeliverableRecipientRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RecipientGuardTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final TenantId OTHER = TenantId.of("tenant-2");
  private static final ChannelType EMAIL = ChannelType.of("EMAIL");
  private static final ChannelType SMS = ChannelType.of("SMS");
  private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

  private final SendNotificationUseCase delegate = mock(SendNotificationUseCase.class);
  private final UndeliverableRecipientRepository repository =
      mock(UndeliverableRecipientRepository.class);
  private final ProviderMessageLookupPort lookup = mock(ProviderMessageLookupPort.class);
  private final NotificationMetricsPort metrics = mock(NotificationMetricsPort.class);
  private final SendNotificationResult accepted =
      new SendNotificationResult(NotificationId.of("n-1"), NotificationStatus.PENDING, false);

  private RecipientGuardedSendNotificationService guard;
  private RecipientBounceService bounces;
  private UndeliverableMarkService marks;

  @BeforeEach
  void setUp() {
    guard = new RecipientGuardedSendNotificationService(delegate, repository, metrics);
    bounces =
        new RecipientBounceService(lookup, repository, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    marks = new UndeliverableMarkService(repository);
    when(delegate.send(any())).thenReturn(Mono.just(accepted));
    when(repository.find(any(), any(), any())).thenReturn(Mono.empty());
  }

  private static SendNotificationCommand command(
      final TenantId tenant, final ChannelType channel, final String address) {
    return new SendNotificationCommand(
        tenant,
        ExternalId.of("ext-1"),
        channel,
        RecipientId.of("rec-1"),
        Recipient.of(address),
        NotificationContent.of("s", "b"),
        Priority.NORMAL);
  }

  private static UndeliverableRecipient mark(final TenantId tenant) {
    return new UndeliverableRecipient(tenant, EMAIL, "bounced@example.com", "hard_bounce", NOW);
  }

  @Test
  void validAddressesAreDelegatedAndEmailIsNormalizedForTheMarkLookup() {
    StepVerifier.create(guard.send(command(TENANT, EMAIL, " Alice@Example.com ")))
        .expectNext(accepted)
        .verifyComplete();
    StepVerifier.create(guard.send(command(TENANT, SMS, "+573001234567")))
        .expectNext(accepted)
        .verifyComplete();
    StepVerifier.create(guard.send(command(TENANT, ChannelType.of("PUSH"), "any-token")))
        .expectNext(accepted)
        .verifyComplete();

    verify(repository).find(TENANT, EMAIL, "alice@example.com");
    verify(delegate, times(3)).send(any());
  }

  @Test
  void malformedAddressesAreRejectedWithoutDelegatingOrLookingUpMarks() {
    StepVerifier.create(guard.send(command(TENANT, SMS, "3001234567")))
        .expectError(InvalidRecipientException.class)
        .verify();
    StepVerifier.create(guard.send(command(TENANT, EMAIL, "not-an-email")))
        .expectError(InvalidRecipientException.class)
        .verify();

    verify(delegate, never()).send(any());
    verify(repository, never()).find(any(), any(), any());
    verify(metrics, times(2)).recipientRejected(any(), any(), eq("format"));
  }

  @Test
  void markedAddressesAreRejectedForTheirTenantOnly() {
    when(repository.find(TENANT, EMAIL, "bounced@example.com")).thenReturn(Mono.just(mark(TENANT)));

    StepVerifier.create(guard.send(command(TENANT, EMAIL, "Bounced@Example.com")))
        .expectError(RecipientUndeliverableException.class)
        .verify();
    StepVerifier.create(guard.send(command(OTHER, EMAIL, "Bounced@Example.com")))
        .expectNext(accepted)
        .verifyComplete();

    verify(delegate, times(1)).send(any());
    verify(metrics).recipientRejected(TENANT, EMAIL, "undeliverable");
  }

  @Test
  void aHardBounceMarksTheNormalizedAddressOfTheOriginalNotificationOnce() {
    final MessageRecipientRef ref =
        new MessageRecipientRef(TENANT, EMAIL, "Bounced@Example.com", NotificationId.of("n-1"));
    when(lookup.findByProviderMessageId("m-1")).thenReturn(Mono.just(ref));
    when(repository.markIfAbsent(any())).thenReturn(Mono.just(true), Mono.just(false));

    StepVerifier.create(bounces.registerHardBounce("m-1", "hard_bounce"))
        .expectNext(true)
        .verifyComplete();
    StepVerifier.create(bounces.registerHardBounce("m-1", "hard_bounce"))
        .expectNext(false)
        .verifyComplete();

    verify(repository, times(2)).markIfAbsent(mark(TENANT));
    verify(metrics, times(1)).recipientMarkedUndeliverable(TENANT, EMAIL);
  }

  @Test
  void bouncesWithoutAResolvableMessageAreDiscarded() {
    when(lookup.findByProviderMessageId("unknown")).thenReturn(Mono.empty());

    StepVerifier.create(bounces.registerHardBounce("unknown", "hard_bounce"))
        .expectNext(false)
        .verifyComplete();
    StepVerifier.create(bounces.registerHardBounce(" ", "hard_bounce"))
        .expectNext(false)
        .verifyComplete();
    StepVerifier.create(bounces.registerHardBounce(null, "hard_bounce"))
        .expectNext(false)
        .verifyComplete();

    verify(metrics, times(3)).bounceUnresolved();
    verify(repository, never()).markIfAbsent(any());
  }

  @Test
  void lookupReportsTheMarkOrItsAbsenceAndLiftingNormalizes() {
    when(repository.find(TENANT, EMAIL, "bounced@example.com")).thenReturn(Mono.just(mark(TENANT)));
    when(repository.remove(any(), any(), any())).thenReturn(Mono.empty());

    StepVerifier.create(marks.find(TENANT, EMAIL, "BOUNCED@example.com"))
        .assertNext(
            view -> {
              assertTrue(view.marked());
              assertEquals("hard_bounce", view.reason());
              assertEquals(NOW, view.markedAt());
            })
        .verifyComplete();
    StepVerifier.create(marks.find(TENANT, EMAIL, "free@example.com"))
        .assertNext(view -> assertFalse(view.marked()))
        .verifyComplete();
    StepVerifier.create(marks.lift(TENANT, EMAIL, "BOUNCED@example.com")).verifyComplete();
    StepVerifier.create(marks.find(TENANT, SMS, "bad"))
        .expectError(InvalidRecipientException.class)
        .verify();

    verify(repository).remove(TENANT, EMAIL, "bounced@example.com");
  }
}
