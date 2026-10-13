package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeliveryAttemptTest {

  @Test
  void preservesGivenValues() {
    final Instant now = Instant.now();

    final DeliveryAttempt attempt =
        DeliveryAttempt.of(
            now, AttemptResult.ACCEPTED, AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(now, attempt.occurredOn());
    assertEquals(AttemptResult.ACCEPTED, attempt.result());
    assertEquals(AttemptOrigin.AUTOMATIC, attempt.origin());
    assertEquals(ProviderId.of("brevo"), attempt.providerId());
  }

  @Test
  void rejectsNullOccurredOn() {
    assertThrows(
        NullPointerException.class,
        () ->
            DeliveryAttempt.of(
                null, AttemptResult.ACCEPTED, AttemptOrigin.MANUAL, ProviderId.of("brevo")));
  }

  @Test
  void rejectsNullResult() {
    assertThrows(
        NullPointerException.class,
        () ->
            DeliveryAttempt.of(Instant.now(), null, AttemptOrigin.MANUAL, ProviderId.of("brevo")));
  }

  @Test
  void rejectsNullOrigin() {
    assertThrows(
        NullPointerException.class,
        () ->
            DeliveryAttempt.of(
                Instant.now(), AttemptResult.ACCEPTED, null, ProviderId.of("brevo")));
  }

  @Test
  void rejectsNullProviderId() {
    assertThrows(
        NullPointerException.class,
        () ->
            DeliveryAttempt.of(Instant.now(), AttemptResult.ACCEPTED, AttemptOrigin.MANUAL, null));
  }

  @Test
  void theCycleDefaultsToOneForAnAttemptCreatedWithoutIt() {
    final DeliveryAttempt attempt =
        DeliveryAttempt.of(
            Instant.now(), AttemptResult.ACCEPTED, AttemptOrigin.AUTOMATIC, ProviderId.of("brevo"));

    assertEquals(1, attempt.cycle());
  }

  @Test
  void anExplicitCycleIsPreserved() {
    final DeliveryAttempt attempt =
        DeliveryAttempt.of(
            Instant.now(),
            AttemptResult.RECOVERABLE_FAILURE,
            AttemptOrigin.MANUAL,
            ProviderId.of("brevo"),
            3);

    assertEquals(3, attempt.cycle());
  }

  @Test
  void aCycleBelowOneIsNormalizedToOneWhenReadingAnOldAttempt() {
    final DeliveryAttempt attempt =
        DeliveryAttempt.of(
            Instant.now(),
            AttemptResult.RECOVERABLE_FAILURE,
            AttemptOrigin.AUTOMATIC,
            ProviderId.of("brevo"),
            0);

    assertEquals(1, attempt.cycle());
  }

  @Test
  void attemptsOfDifferentCyclesAreNotEqual() {
    final Instant now = Instant.now();
    assertNotEquals(
        DeliveryAttempt.of(
            now,
            AttemptResult.RECOVERABLE_FAILURE,
            AttemptOrigin.AUTOMATIC,
            ProviderId.of("brevo"),
            1),
        DeliveryAttempt.of(
            now,
            AttemptResult.RECOVERABLE_FAILURE,
            AttemptOrigin.AUTOMATIC,
            ProviderId.of("brevo"),
            2));
  }

  @Test
  void equalityIsByValue() {
    final Instant now = Instant.now();
    assertEquals(
        DeliveryAttempt.of(
            now, AttemptResult.RECOVERABLE_FAILURE, AttemptOrigin.MANUAL, ProviderId.of("brevo")),
        DeliveryAttempt.of(
            now, AttemptResult.RECOVERABLE_FAILURE, AttemptOrigin.MANUAL, ProviderId.of("brevo")));
  }
}
