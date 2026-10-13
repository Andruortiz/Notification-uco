package co.edu.uco.notification.core.domain.configuration;

import co.edu.uco.notification.utils.Preconditions;
import java.util.Set;
import java.util.TreeSet;

public record ConfigurationChangeOutcome(
    Status status,
    String reason,
    Set<String> keys,
    Set<String> pendingRestartKeys,
    long previousVersion,
    long newVersion) {

  public enum Status {
    APPLIED,
    PENDING_RESTART,
    IGNORED_STALE,
    REJECTED
  }

  public ConfigurationChangeOutcome {
    Preconditions.requireNonNull(status, "status must not be null");
    keys = keys == null ? Set.of() : Set.copyOf(new TreeSet<>(keys));
    pendingRestartKeys =
        pendingRestartKeys == null ? Set.of() : Set.copyOf(new TreeSet<>(pendingRestartKeys));
  }

  public static ConfigurationChangeOutcome applied(
      final Set<String> keys,
      final Set<String> pendingRestartKeys,
      final long previousVersion,
      final long newVersion) {
    return new ConfigurationChangeOutcome(
        Status.APPLIED, null, keys, pendingRestartKeys, previousVersion, newVersion);
  }

  public static ConfigurationChangeOutcome pendingRestart(
      final Set<String> pendingRestartKeys, final long previousVersion, final long newVersion) {
    return new ConfigurationChangeOutcome(
        Status.PENDING_RESTART, null, Set.of(), pendingRestartKeys, previousVersion, newVersion);
  }

  public static ConfigurationChangeOutcome ignoredStale(
      final long currentVersion, final long receivedVersion) {
    return new ConfigurationChangeOutcome(
        Status.IGNORED_STALE,
        "version " + receivedVersion + " is not newer than " + currentVersion,
        Set.of(),
        Set.of(),
        currentVersion,
        currentVersion);
  }

  public static ConfigurationChangeOutcome rejected(
      final String reason, final Set<String> keys, final long currentVersion) {
    return new ConfigurationChangeOutcome(
        Status.REJECTED, reason, keys, Set.of(), currentVersion, currentVersion);
  }
}
