package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.in.ChannelProviderView;
import co.edu.uco.notification.core.port.in.ChannelView;
import co.edu.uco.notification.core.port.in.ProviderChannelView;
import co.edu.uco.notification.core.port.in.ProviderStatus;
import co.edu.uco.notification.core.port.in.ProviderView;
import co.edu.uco.notification.core.port.in.QueryChannelCatalogUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.utils.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;

public final class QueryChannelCatalogService implements QueryChannelCatalogUseCase {

  private static final String MISSING_ADAPTER_REASON =
      "no notification sender registered for this provider";

  private final ChannelCatalogPort channelCatalogPort;
  private final NotificationSenderRegistry notificationSenderRegistry;

  public QueryChannelCatalogService(
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry notificationSenderRegistry) {
    this.channelCatalogPort =
        Preconditions.requireNonNull(channelCatalogPort, "channelCatalogPort must not be null");
    this.notificationSenderRegistry =
        Preconditions.requireNonNull(
            notificationSenderRegistry, "notificationSenderRegistry must not be null");
  }

  @Override
  public Mono<List<ChannelView>> listChannels() {
    return channelCatalogPort
        .findAllRoutes()
        .map(this::toChannelView)
        .collectSortedList(Comparator.comparing(channel -> channel.channelType().value()));
  }

  @Override
  public Mono<List<ProviderView>> listProviders() {
    return channelCatalogPort.findAllRoutes().collectList().map(this::toProviderViews);
  }

  private List<ProviderView> toProviderViews(final List<ChannelRoute> routes) {
    final Map<ProviderId, List<ProviderChannelView>> channelsByProvider = new HashMap<>();
    for (final ProviderId providerId : notificationSenderRegistry.providerIds()) {
      channelsByProvider.put(providerId, new ArrayList<>());
    }
    for (final ChannelRoute route : routes) {
      for (int index = 0; index < route.providers().size(); index++) {
        channelsByProvider
            .computeIfAbsent(route.providers().get(index), providerId -> new ArrayList<>())
            .add(new ProviderChannelView(route.channelType(), index + 1));
      }
    }
    return channelsByProvider.entrySet().stream()
        .map(entry -> toProviderView(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(provider -> provider.providerId().value()))
        .toList();
  }

  private ProviderView toProviderView(
      final ProviderId providerId, final List<ProviderChannelView> channels) {
    final ProviderState state = stateOf(providerId);
    final List<ProviderChannelView> sortedChannels =
        channels.stream()
            .sorted(
                Comparator.comparing((ProviderChannelView channel) -> channel.channelType().value())
                    .thenComparingInt(ProviderChannelView::preferenceOrder))
            .toList();
    return new ProviderView(providerId, state.status(), state.reason(), sortedChannels);
  }

  private ChannelView toChannelView(final ChannelRoute route) {
    final List<ChannelProviderView> providers = new ArrayList<>();
    for (int index = 0; index < route.providers().size(); index++) {
      final ProviderId providerId = route.providers().get(index);
      final ProviderState state = stateOf(providerId);
      providers.add(new ChannelProviderView(providerId, index + 1, state.status(), state.reason()));
    }
    return new ChannelView(route.channelType(), blankToNull(route.contentSchema()), providers);
  }

  private ProviderState stateOf(final ProviderId providerId) {
    final Optional<NotificationSenderPort> sender = notificationSenderRegistry.find(providerId);
    if (sender.isEmpty()) {
      return new ProviderState(ProviderStatus.MISSING_ADAPTER, MISSING_ADAPTER_REASON);
    }
    return sender
        .get()
        .disabledReason()
        .map(reason -> new ProviderState(ProviderStatus.DISABLED, reason))
        .orElseGet(() -> new ProviderState(ProviderStatus.ENABLED, null));
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record ProviderState(ProviderStatus status, String reason) {}
}
