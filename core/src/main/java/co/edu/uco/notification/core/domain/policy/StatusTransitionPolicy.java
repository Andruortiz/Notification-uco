package co.edu.uco.notification.core.domain.policy;

import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class StatusTransitionPolicy {

  private static final Map<NotificationStatus, Set<NotificationStatus>> VALID_TRANSITIONS =
      new EnumMap<>(NotificationStatus.class);

  static {
    VALID_TRANSITIONS.put(
        NotificationStatus.PENDING,
        EnumSet.of(NotificationStatus.IN_PROCESS, NotificationStatus.DISCARDED));
    VALID_TRANSITIONS.put(
        NotificationStatus.IN_PROCESS,
        EnumSet.of(
            NotificationStatus.DELIVERED,
            NotificationStatus.RECOVERABLE,
            NotificationStatus.FAILED));
    VALID_TRANSITIONS.put(NotificationStatus.RECOVERABLE, EnumSet.of(NotificationStatus.PENDING));
    VALID_TRANSITIONS.put(NotificationStatus.FAILED, EnumSet.of(NotificationStatus.PENDING));
    VALID_TRANSITIONS.put(NotificationStatus.DELIVERED, EnumSet.noneOf(NotificationStatus.class));
    VALID_TRANSITIONS.put(NotificationStatus.DISCARDED, EnumSet.noneOf(NotificationStatus.class));
  }

  private StatusTransitionPolicy() {}

  public static boolean canTransition(final NotificationStatus from, final NotificationStatus to) {
    return VALID_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
  }
}
