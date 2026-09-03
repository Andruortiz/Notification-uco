package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ProviderId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ConfigurationChannelCatalogAdapterTest {

  private static ConfigurationChannelCatalogAdapter adapterWith(
      final Map<String, ChannelCatalogProperties.ChannelEntry> channels) {
    return new ConfigurationChannelCatalogAdapter(new ChannelCatalogProperties(channels));
  }

  @Test
  void findActiveRouteReturnsRouteForConfiguredChannel() {
    final ConfigurationChannelCatalogAdapter adapter =
        adapterWith(
            Map.of(
                "EMAIL",
                new ChannelCatalogProperties.ChannelEntry(
                    List.of("brevo", "sendgrid"), "{\"type\":\"object\"}")));

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("EMAIL"), null))
        .assertNext(
            route -> {
              assertEquals(ChannelType.of("EMAIL"), route.channelType());
              assertEquals(
                  List.of(ProviderId.of("brevo"), ProviderId.of("sendgrid")), route.providers());
              assertEquals("{\"type\":\"object\"}", route.contentSchema());
            })
        .verifyComplete();
  }

  @Test
  void lookupIsCaseInsensitive() {
    final ConfigurationChannelCatalogAdapter adapter =
        adapterWith(
            Map.of("EMAIL", new ChannelCatalogProperties.ChannelEntry(List.of("brevo"), null)));

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("email"), null))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void findActiveRouteIsEmptyForUnknownChannel() {
    final ConfigurationChannelCatalogAdapter adapter = adapterWith(Map.of());

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("SMS"), null)).verifyComplete();
  }

  @Test
  void findActiveRouteIsEmptyWhenChannelHasNoProviders() {
    final ConfigurationChannelCatalogAdapter adapter =
        adapterWith(Map.of("EMAIL", new ChannelCatalogProperties.ChannelEntry(List.of(), null)));

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("EMAIL"), null)).verifyComplete();
  }

  @Test
  void findActiveRouteIsEmptyWhenProvidersIsNull() {
    final ConfigurationChannelCatalogAdapter adapter =
        adapterWith(Map.of("EMAIL", new ChannelCatalogProperties.ChannelEntry(null, null)));

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("EMAIL"), null)).verifyComplete();
  }

  @Test
  void findActiveRouteIsEmptyWhenChannelsMapIsNull() {
    final ConfigurationChannelCatalogAdapter adapter = adapterWith(null);

    StepVerifier.create(adapter.findActiveRoute(ChannelType.of("EMAIL"), null)).verifyComplete();
  }

  @Test
  void findActiveRouteRejectsNullChannel() {
    final ConfigurationChannelCatalogAdapter adapter = adapterWith(Map.of());

    assertThrows(NullPointerException.class, () -> adapter.findActiveRoute(null, null));
  }

  @Test
  void constructorRejectsNullProperties() {
    assertThrows(NullPointerException.class, () -> new ConfigurationChannelCatalogAdapter(null));
  }
}
