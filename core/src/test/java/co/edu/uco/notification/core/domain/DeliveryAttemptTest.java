package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void equalityIsByValue() {
    final Instant now = Instant.now();
    assertEquals(
        DeliveryAttempt.of(
            now, AttemptResult.RECOVERABLE_FAILURE, AttemptOrigin.MANUAL, ProviderId.of("brevo")),
        DeliveryAttempt.of(
            now, AttemptResult.RECOVERABLE_FAILURE, AttemptOrigin.MANUAL, ProviderId.of("brevo")));
  }
}
