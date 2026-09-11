package co.edu.uco.notification.core.domain.policy;

import co.edu.uco.notification.utils.Preconditions;
import java.time.Duration;

public final class RetryPolicy {

  private static final int DEFAULT_MAX_ATTEMPTS = 5;
  private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

  private final int maxAttempts;

  public RetryPolicy() {
    this(DEFAULT_MAX_ATTEMPTS);
  }

  public RetryPolicy(final int maxAttempts) {
    Preconditions.requireTrue(maxAttempts > 0, "maxAttempts must be positive");
    this.maxAttempts = maxAttempts;
  }

  public boolean shouldGiveUp(final int recoverableAttemptCount) {
    Preconditions.requireTrue(
        recoverableAttemptCount > 0, "recoverableAttemptCount must be positive");
    return recoverableAttemptCount >= maxAttempts;
  }

  public Duration nextBackoff(final int recoverableAttemptCount) {
    Preconditions.requireTrue(
        recoverableAttemptCount > 0, "recoverableAttemptCount must be positive");
    final int shift = Math.min(recoverableAttemptCount - 1, 30);
    final Duration backoff = BASE_BACKOFF.multipliedBy(1L << shift);
    return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
  }
}
