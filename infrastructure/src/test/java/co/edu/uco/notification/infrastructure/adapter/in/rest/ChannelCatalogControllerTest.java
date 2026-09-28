package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.in.ChannelProviderView;
import co.edu.uco.notification.core.port.in.ChannelView;
import co.edu.uco.notification.core.port.in.ProviderChannelView;
import co.edu.uco.notification.core.port.in.ProviderStatus;
import co.edu.uco.notification.core.port.in.ProviderView;
import co.edu.uco.notification.core.port.in.QueryChannelCatalogUseCase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = ChannelCatalogController.class)
class ChannelCatalogControllerTest {

  private static final String DISABLED_REASON =
      "missing notification.provider.brevo.api-key (BREVO_API_KEY)";

  @Autowired private WebTestClient webTestClient;

  @MockBean private QueryChannelCatalogUseCase queryChannelCatalogUseCase;

  @Test
  void listChannelsMapsEveryFieldAndKeepsAbsentValuesAsExplicitNulls() {
    when(queryChannelCatalogUseCase.listChannels())
        .thenReturn(
            Mono.just(
                List.of(
                    new ChannelView(
                        ChannelType.of("EMAIL"),
                        null,
                        List.of(
                            new ChannelProviderView(
                                ProviderId.of("simulated"), 1, ProviderStatus.ENABLED, null),
                            new ChannelProviderView(
                                ProviderId.of("brevo"),
                                2,
                                ProviderStatus.DISABLED,
                                DISABLED_REASON))),
                    new ChannelView(
                        ChannelType.of("SMS"),
                        "{\"type\":\"object\"}",
                        List.of(
                            new ChannelProviderView(
                                ProviderId.of("ghost"),
                                1,
                                ProviderStatus.MISSING_ADAPTER,
                                "no notification sender registered for this provider"))))));

    webTestClient
        .get()
        .uri("/channels")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(2)
        .jsonPath("$.items[0].channelType")
        .isEqualTo("EMAIL")
        .jsonPath("$.items[0].providers[0].providerId")
        .isEqualTo("simulated")
        .jsonPath("$.items[0].providers[0].preferenceOrder")
        .isEqualTo(1)
        .jsonPath("$.items[0].providers[0].status")
        .isEqualTo("ENABLED")
        .jsonPath("$.items[0].providers[1].providerId")
        .isEqualTo("brevo")
        .jsonPath("$.items[0].providers[1].preferenceOrder")
        .isEqualTo(2)
        .jsonPath("$.items[0].providers[1].status")
        .isEqualTo("DISABLED")
        .jsonPath("$.items[0].providers[1].statusReason")
        .isEqualTo(DISABLED_REASON)
        .jsonPath("$.items[1].contentSchema")
        .isEqualTo("{\"type\":\"object\"}")
        .jsonPath("$.items[1].providers[0].status")
        .isEqualTo("MISSING_ADAPTER");
  }

  @Test
  void listChannelsSerializesNullValuesExplicitly() {
    when(queryChannelCatalogUseCase.listChannels())
        .thenReturn(
            Mono.just(
                List.of(
                    new ChannelView(
                        ChannelType.of("EMAIL"),
                        null,
                        List.of(
                            new ChannelProviderView(
                                ProviderId.of("simulated"), 1, ProviderStatus.ENABLED, null))))));

    webTestClient
        .get()
        .uri("/channels")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json(
            """
            {"items":[{"channelType":"EMAIL","contentSchema":null,"providers":[
              {"providerId":"simulated","preferenceOrder":1,"status":"ENABLED","statusReason":null}
            ]}]}
            """,
            true);
  }

  @Test
  void listChannelsReturnsAnEmptyListWhenTheCatalogIsEmpty() {
    when(queryChannelCatalogUseCase.listChannels()).thenReturn(Mono.just(List.of()));

    webTestClient
        .get()
        .uri("/channels")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json("{\"items\":[]}", true);
  }

  @Test
  void listProvidersMapsEveryFieldIncludingProvidersWithoutChannels() {
    when(queryChannelCatalogUseCase.listProviders())
        .thenReturn(
            Mono.just(
                List.of(
                    new ProviderView(
                        ProviderId.of("brevo"),
                        ProviderStatus.DISABLED,
                        DISABLED_REASON,
                        List.of(new ProviderChannelView(ChannelType.of("EMAIL"), 2))),
                    new ProviderView(
                        ProviderId.of("simulated"), ProviderStatus.ENABLED, null, List.of()))));

    webTestClient
        .get()
        .uri("/providers")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json(
            """
            {"items":[
              {"providerId":"brevo","status":"DISABLED","statusReason":"%s",
               "channels":[{"channelType":"EMAIL","preferenceOrder":2}]},
              {"providerId":"simulated","status":"ENABLED","statusReason":null,"channels":[]}
            ]}
            """
                .formatted(DISABLED_REASON),
            true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/channels", "/providers"})
  void rejectsARequestWithoutTenantWithoutQueryingTheCatalog(final String path) {
    webTestClient
        .get()
        .uri(path)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("TenantId must not be blank");

    verifyNoInteractions(queryChannelCatalogUseCase);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/channels", "/providers"})
  void rejectsARequestWithABlankTenantWithoutQueryingTheCatalog(final String path) {
    webTestClient
        .get()
        .uri(path)
        .header("X-Tenant-Id", "   ")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("TenantId must not be blank");

    verifyNoInteractions(queryChannelCatalogUseCase);
  }
}
