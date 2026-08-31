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
        DeliveryAttempt.of(now, AttemptResult.ACCEPTED, AttemptOrigin.AUTOMATIC);

    assertEquals(now, attempt.occurredOn());
    assertEquals(AttemptResult.ACCEPTED, attempt.result());
    assertEquals(AttemptOrigin.AUTOMATIC, attempt.origin());
  }

  @Test
  void rejectsNullOccurredOn() {
    assertThrows(
        NullPointerException.class,
        () -> DeliveryAttempt.of(null, AttemptResult.ACCEPTED, AttemptOrigin.MANUAL));
  }

  @Test
  void rejectsNullResult() {
    assertThrows(
        NullPointerException.class,
        () -> DeliveryAttempt.of(Instant.now(), null, AttemptOrigin.MANUAL));
  }

  @Test
  void rejectsNullOrigin() {
    assertThrows(
        NullPointerException.class,
        () -> DeliveryAttempt.of(Instant.now(), AttemptResult.ACCEPTED, null));
  }

  @Test
  void equalityIsByValue() {
    final Instant now = Instant.now();
    assertEquals(
        DeliveryAttempt.of(now, AttemptResult.RECOVERABLE_FAILURE, AttemptOrigin.MANUAL),
        DeliveryAttempt.of(now, AttemptResult.RECOVERABLE_FAILURE, AttemptOrigin.MANUAL));
  }
}
