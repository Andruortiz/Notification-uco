package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderNotAvailableException;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class NotificationSenderRegistry {

  private final Map<ProviderId, NotificationSenderPort> sendersByProviderId;

  public NotificationSenderRegistry(final Collection<NotificationSenderPort> senders) {
    Preconditions.requireNonNull(senders, "senders must not be null");
    final Map<ProviderId, NotificationSenderPort> byProviderId = new HashMap<>();
    for (final NotificationSenderPort sender : senders) {
      Preconditions.requireNonNull(sender, "sender must not be null");
      final ProviderId providerId =
          Preconditions.requireNonNull(sender.providerId(), "sender providerId must not be null");
      Preconditions.requireTrue(
          !byProviderId.containsKey(providerId),
          "Duplicate notification sender for provider: " + providerId.value());
      byProviderId.put(providerId, sender);
    }
    this.sendersByProviderId = Map.copyOf(byProviderId);
  }

  public NotificationSenderPort resolve(final ProviderId providerId) {
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    final NotificationSenderPort sender = sendersByProviderId.get(providerId);
    if (sender == null) {
      throw new ProviderNotAvailableException(providerId);
    }
    return sender;
  }

  public Optional<NotificationSenderPort> find(final ProviderId providerId) {
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    return Optional.ofNullable(sendersByProviderId.get(providerId));
  }

  public Set<ProviderId> providerIds() {
    return sendersByProviderId.keySet();
  }
}
