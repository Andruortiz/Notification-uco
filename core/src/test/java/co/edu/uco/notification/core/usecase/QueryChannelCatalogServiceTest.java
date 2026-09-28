package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.in.ChannelProviderView;
import co.edu.uco.notification.core.port.in.ChannelView;
import co.edu.uco.notification.core.port.in.ProviderChannelView;
import co.edu.uco.notification.core.port.in.ProviderStatus;
import co.edu.uco.notification.core.port.in.ProviderView;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class QueryChannelCatalogServiceTest {

  private static final String MISSING_ADAPTER_REASON =
      "no notification sender registered for this provider";

  private final ChannelCatalogPort channelCatalogPort = mock(ChannelCatalogPort.class);

  private record FakeSender(ProviderId providerId, Optional<String> disabledReason)
      implements NotificationSenderPort {

    @Override
    public Mono<AttemptResult> send(final Notification notification) {
      return Mono.just(AttemptResult.ACCEPTED);
    }
  }

  private static NotificationSenderPort enabled(final String providerId) {
    return new FakeSender(ProviderId.of(providerId), Optional.empty());
  }

  private static NotificationSenderPort disabled(final String providerId, final String reason) {
    return new FakeSender(ProviderId.of(providerId), Optional.of(reason));
  }

  private static ChannelRoute route(
      final String channel, final String contentSchema, final String... providers) {
    return new ChannelRoute(
        ChannelType.of(channel),
        Arrays.stream(providers).map(ProviderId::of).toList(),
        contentSchema);
  }

  private QueryChannelCatalogService serviceWith(
      final List<ChannelRoute> routes, final NotificationSenderPort... senders) {
    when(channelCatalogPort.findAllRoutes()).thenReturn(Flux.fromIterable(routes));
    return new QueryChannelCatalogService(
        channelCatalogPort, new NotificationSenderRegistry(List.of(senders)));
  }

  private static ChannelProviderView enabledAt(final String providerId, final int order) {
    return new ChannelProviderView(ProviderId.of(providerId), order, ProviderStatus.ENABLED, null);
  }

  @Test
  void listChannelsOrdersChannelsAlphabeticallyAndProvidersByPreference() {
    final QueryChannelCatalogService service =
        serviceWith(
            List.of(
                route("SMS", null, "b-provider", "a-provider"),
                route("EMAIL", null, "a-provider"),
                route("PUSH", null, "a-provider")),
            enabled("a-provider"),
            enabled("b-provider"));

    StepVerifier.create(service.listChannels())
        .assertNext(
            channels -> {
              assertEquals(
                  List.of("EMAIL", "PUSH", "SMS"),
                  channels.stream().map(channel -> channel.channelType().value()).toList());
              assertEquals(
                  List.of(enabledAt("b-provider", 1), enabledAt("a-provider", 2)),
                  channels.get(2).providers());
            })
        .verifyComplete();
  }

  @Test
  void listChannelsKeepsARepeatedProviderInEachOfItsPositions() {
    final QueryChannelCatalogService service =
        serviceWith(
            List.of(route("EMAIL", null, "a-provider", "a-provider")), enabled("a-provider"));

    StepVerifier.create(service.listChannels())
        .assertNext(
            channels ->
                assertEquals(
                    List.of(enabledAt("a-provider", 1), enabledAt("a-provider", 2)),
                    channels.get(0).providers()))
        .verifyComplete();
  }

  @Test
  void listChannelsReportsABlankOrMissingContentSchemaAsAbsentAndKeepsADeclaredOne() {
    final String schema = "{\"type\":\"object\"}";
    final QueryChannelCatalogService service =
        serviceWith(
            List.of(
                route("EMAIL", null, "a-provider"),
                route("PUSH", "   ", "a-provider"),
                route("SMS", schema, "a-provider")),
            enabled("a-provider"));

    StepVerifier.create(service.listChannels())
        .assertNext(
            channels -> {
              assertEquals(null, channels.get(0).contentSchema());
              assertEquals(null, channels.get(1).contentSchema());
              assertEquals(schema, channels.get(2).contentSchema());
            })
        .verifyComplete();
  }

  @Test
  void listChannelsIsEmptyWhenTheCatalogIsEmpty() {
    final QueryChannelCatalogService service = serviceWith(List.of(), enabled("a-provider"));

    StepVerifier.create(service.listChannels()).expectNext(List.of()).verifyComplete();
  }

  @Test
  void listChannelsDerivesTheStatusOfEachProviderFromItsAdapter() {
    final QueryChannelCatalogService service =
        serviceWith(
            List.of(route("EMAIL", null, "enabled-provider", "disabled-provider", "ghost")),
            enabled("enabled-provider"),
            disabled("disabled-provider", "missing some.setting (SOME_SETTING)"));

    StepVerifier.create(service.listChannels())
        .assertNext(
            channels ->
                assertEquals(
                    List.of(
                        enabledAt("enabled-provider", 1),
                        new ChannelProviderView(
                            ProviderId.of("disabled-provider"),
                            2,
                            ProviderStatus.DISABLED,
                            "missing some.setting (SOME_SETTING)"),
                        new ChannelProviderView(
                            ProviderId.of("ghost"),
                            3,
                            ProviderStatus.MISSING_ADAPTER,
                            MISSING_ADAPTER_REASON)),
                    channels.get(0).providers()))
        .verifyComplete();
  }

  @Test
  void listChannelsReturnsViewsWithTheRoutingChannelIdentifier() {
    final QueryChannelCatalogService service =
        serviceWith(List.of(route("EMAIL", null, "a-provider")), enabled("a-provider"));

    StepVerifier.create(service.listChannels())
        .expectNext(
            List.of(
                new ChannelView(
                    ChannelType.of("EMAIL"), null, List.of(enabledAt("a-provider", 1)))))
        .verifyComplete();
  }

  private static ProviderChannelView at(final String channel, final int order) {
    return new ProviderChannelView(ChannelType.of(channel), order);
  }

  @Test
  void listProvidersListsAProviderUsedByManyChannelsOnceWithEveryChannel() {
    final QueryChannelCatalogService service =
        serviceWith(
            List.of(route("SMS", null, "shared", "other"), route("EMAIL", null, "other", "shared")),
            enabled("shared"),
            enabled("other"));

    StepVerifier.create(service.listProviders())
        .assertNext(
            providers ->
                assertEquals(
                    List.of(
                        new ProviderView(
                            ProviderId.of("other"),
                            ProviderStatus.ENABLED,
                            null,
                            List.of(at("EMAIL", 1), at("SMS", 2))),
                        new ProviderView(
                            ProviderId.of("shared"),
                            ProviderStatus.ENABLED,
                            null,
                            List.of(at("EMAIL", 2), at("SMS", 1)))),
                    providers))
        .verifyComplete();
  }

  @Test
  void listProvidersIsTheUnionOfAdaptersAndCatalogProviders() {
    final QueryChannelCatalogService service =
        serviceWith(
            List.of(route("EMAIL", null, "ghost", "routed")),
            enabled("routed"),
            disabled("unrouted", "missing some.setting (SOME_SETTING)"));

    StepVerifier.create(service.listProviders())
        .assertNext(
            providers ->
                assertEquals(
                    List.of(
                        new ProviderView(
                            ProviderId.of("ghost"),
                            ProviderStatus.MISSING_ADAPTER,
                            MISSING_ADAPTER_REASON,
                            List.of(at("EMAIL", 1))),
                        new ProviderView(
                            ProviderId.of("routed"),
                            ProviderStatus.ENABLED,
                            null,
                            List.of(at("EMAIL", 2))),
                        new ProviderView(
                            ProviderId.of("unrouted"),
                            ProviderStatus.DISABLED,
                            "missing some.setting (SOME_SETTING)",
                            List.of())),
                    providers))
        .verifyComplete();
  }

  @Test
  void listProvidersKeepsEveryPositionOfAProviderRepeatedInAChannel() {
    final QueryChannelCatalogService service =
        serviceWith(
            List.of(route("EMAIL", null, "a-provider", "a-provider")), enabled("a-provider"));

    StepVerifier.create(service.listProviders())
        .assertNext(
            providers ->
                assertEquals(List.of(at("EMAIL", 1), at("EMAIL", 2)), providers.get(0).channels()))
        .verifyComplete();
  }

  @Test
  void listProvidersListsOnlyTheAdaptersWhenTheCatalogIsEmpty() {
    final QueryChannelCatalogService service =
        serviceWith(List.of(), enabled("b-provider"), enabled("a-provider"));

    StepVerifier.create(service.listProviders())
        .assertNext(
            providers ->
                assertEquals(
                    List.of(
                        new ProviderView(
                            ProviderId.of("a-provider"), ProviderStatus.ENABLED, null, List.of()),
                        new ProviderView(
                            ProviderId.of("b-provider"), ProviderStatus.ENABLED, null, List.of())),
                    providers))
        .verifyComplete();
  }

  @Test
  void constructorRejectsNullDependencies() {
    final NotificationSenderRegistry registry = new NotificationSenderRegistry(List.of());

    assertThrows(NullPointerException.class, () -> new QueryChannelCatalogService(null, registry));
    assertThrows(
        NullPointerException.class, () -> new QueryChannelCatalogService(channelCatalogPort, null));
  }
}
