package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.policy.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  @Test
  void defaultPolicyGivesUpAtFiveAttempts() {
    final RetryPolicy policy = new RetryPolicy();

    assertFalse(policy.shouldGiveUp(4));
    assertTrue(policy.shouldGiveUp(5));
  }

  @Test
  void customMaxAttemptsIsRespected() {
    final RetryPolicy policy = new RetryPolicy(2);

    assertFalse(policy.shouldGiveUp(1));
    assertTrue(policy.shouldGiveUp(2));
  }

  @Test
  void rejectsNonPositiveMaxAttempts() {
    assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0));
    assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1));
  }

  @Test
  void shouldGiveUpRejectsNonPositiveAttemptCount() {
    final RetryPolicy policy = new RetryPolicy();
    assertThrows(IllegalArgumentException.class, () -> policy.shouldGiveUp(0));
  }

  @Test
  void nextBackoffDoublesWithEachAttempt() {
    final RetryPolicy policy = new RetryPolicy();

    assertEquals(Duration.ofSeconds(30), policy.nextBackoff(1));
    assertEquals(Duration.ofSeconds(60), policy.nextBackoff(2));
    assertEquals(Duration.ofSeconds(120), policy.nextBackoff(3));
  }

  @Test
  void nextBackoffIsCappedAtThirtyMinutes() {
    final RetryPolicy policy = new RetryPolicy();

    assertEquals(Duration.ofMinutes(30), policy.nextBackoff(20));
  }

  @Test
  void nextBackoffRejectsNonPositiveAttemptCount() {
    final RetryPolicy policy = new RetryPolicy();
    assertThrows(IllegalArgumentException.class, () -> policy.nextBackoff(0));
  }
}
