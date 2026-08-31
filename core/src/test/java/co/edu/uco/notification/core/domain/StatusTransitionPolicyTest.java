package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatusTransitionPolicyTest {

  @ParameterizedTest
  @CsvSource({
    "PENDING, IN_PROCESS",
    "PENDING, DISCARDED",
    "IN_PROCESS, DELIVERED",
    "IN_PROCESS, RECOVERABLE",
    "IN_PROCESS, FAILED",
    "RECOVERABLE, PENDING",
    "FAILED, PENDING"
  })
  void allowsValidTransitions(final NotificationStatus from, final NotificationStatus to) {
    assertTrue(StatusTransitionPolicy.canTransition(from, to));
  }

  @ParameterizedTest
  @CsvSource({
    "DELIVERED, PENDING",
    "DISCARDED, PENDING",
    "PENDING, DELIVERED",
    "PENDING, FAILED",
    "IN_PROCESS, DISCARDED",
    "RECOVERABLE, DELIVERED"
  })
  void rejectsInvalidTransitions(final NotificationStatus from, final NotificationStatus to) {
    assertFalse(StatusTransitionPolicy.canTransition(from, to));
  }

  @ParameterizedTest
  @CsvSource({"DELIVERED", "DISCARDED"})
  void terminalStatusesAllowNoOutgoingTransition(final NotificationStatus terminal) {
    for (final NotificationStatus status : NotificationStatus.values()) {
      assertFalse(StatusTransitionPolicy.canTransition(terminal, status));
    }
  }
}
