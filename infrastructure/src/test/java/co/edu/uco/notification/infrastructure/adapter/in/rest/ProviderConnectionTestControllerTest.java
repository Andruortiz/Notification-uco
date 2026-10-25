package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.exception.ProviderTestInProgressException;
import co.edu.uco.notification.core.port.in.ProviderProbeResult;
import co.edu.uco.notification.core.port.in.TestProviderConnectionUseCase;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticatedPrincipalArgumentResolver;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticationWebFilter;
import co.edu.uco.notification.infrastructure.adapter.in.web.RouteAuthorizationPolicy;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.config.SecurityConfig;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@AutoConfigureWebTestClient(timeout = "30s")
@WebFluxTest(controllers = ProviderConnectionTestController.class)
@Import({
  SecurityConfig.class,
  AuthenticationWebFilter.class,
  LocalJwtTokenValidationAdapter.class,
  RouteAuthorizationPolicy.class,
  AuthenticatedPrincipalArgumentResolver.class,
  ProviderConnectionTestControllerTest.MetricsConfiguration.class
})
class ProviderConnectionTestControllerTest {

  private static final String SENTINEL = "sentinel-credential-9f3a7c";

  @TestConfiguration
  static class MetricsConfiguration {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Autowired private WebTestClient webTestClient;
  @MockitoSpyBean private MeterRegistry meterRegistry;

  @MockitoBean private TestProviderConnectionUseCase testProviderConnectionUseCase;

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    logs = new ConcurrentListAppender();
    logs.start();
    rootLogger().setLevel(Level.INFO);
    rootLogger().addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    rootLogger().detachAppender(logs);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private WebTestClient.ResponseSpec post(final String bearer, final Map<String, String> body) {
    return webTestClient
        .post()
        .uri("/provider-connection-tests")
        .header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private static ProviderProbeResult result(final ProbeErrorCode code) {
    return new ProviderProbeResult(ProviderId.of("brevo"), ChannelType.of("EMAIL"), 42L, code);
  }

  private String probeLogs() {
    return logs.list.stream()
        .filter(event -> event.getFormattedMessage().contains("Provider connection test"))
        .map(
            event ->
                event.getFormattedMessage() + event.getMarkerList() + event.getMDCPropertyMap())
        .collect(Collectors.joining("\n"));
  }

  @Test
  void anAdministratorGetsTheResultAndItIsCountedLoggedAndFreeOfSecrets() {
    when(testProviderConnectionUseCase.test(any(), any()))
        .thenReturn(Mono.just(result(ProbeErrorCode.AUTH_REJECTED)));

    post(TestTokens.bearer("tenant-1"), Map.of("channel", "EMAIL", "providerId", "brevo"))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.providerId")
        .isEqualTo("brevo")
        .jsonPath("$.channel")
        .isEqualTo("EMAIL")
        .jsonPath("$.status")
        .isEqualTo("FAILED")
        .jsonPath("$.latencyMs")
        .isEqualTo(42)
        .jsonPath("$.errorCode")
        .isEqualTo("AUTH_REJECTED");

    final var counter =
        meterRegistry
            .find(ProviderConnectionTestController.METRIC_NAME)
            .tags("providerId", "brevo", "status", "FAILED")
            .counter();
    assertNotNull(counter);
    assertEquals(1.0, counter.count());
    final var tagKeys =
        meterRegistry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .map(Tag::getKey)
            .collect(Collectors.toSet());
    assertEquals(java.util.Set.of("providerId", "status"), tagKeys);
    final String text = probeLogs();
    assertTrue(text.contains("AUTH_REJECTED"), text);
    assertTrue(text.contains("tenant-1"), text);
    assertTrue(text.contains("test-client"), text);
    assertFalse(text.contains(SENTINEL));
  }

  @Test
  void anOkResultHasNullErrorCode() {
    when(testProviderConnectionUseCase.test(any(), any())).thenReturn(Mono.just(result(null)));

    post(TestTokens.bearer("tenant-1"), Map.of("channel", "EMAIL", "providerId", "brevo"))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("OK")
        .jsonPath("$.errorCode")
        .isEmpty();
  }

  @Test
  void clienteAndOperadorAreForbiddenAndNoProbeRuns() {
    for (final Role role : new Role[] {Role.CLIENTE, Role.OPERADOR}) {
      post(TestTokens.bearer("tenant-1", role), Map.of("channel", "EMAIL", "providerId", "brevo"))
          .expectStatus()
          .isForbidden();
    }
    verifyNoInteractions(testProviderConnectionUseCase);
  }

  @Test
  void aTestAlreadyInFlightIsAnswered429WithItsErrorCode() {
    when(testProviderConnectionUseCase.test(any(), any()))
        .thenReturn(Mono.error(new ProviderTestInProgressException()));

    post(TestTokens.bearer("tenant-1"), Map.of("channel", "EMAIL", "providerId", "brevo"))
        .expectStatus()
        .isEqualTo(429)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-3060");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "{}",
        "{\"channel\":null,\"providerId\":\"brevo\"}",
        "{\"channel\":\"\",\"providerId\":\"brevo\"}",
        "{\"channel\":\"  \",\"providerId\":\"brevo\"}",
        "{\"channel\":\"EMAIL\"}",
        "{\"channel\":\"EMAIL\",\"providerId\":null}",
        "{\"channel\":\"EMAIL\",\"providerId\":\"\"}",
        "{\"channel\":\"EMAIL\",\"providerId\":\"   \"}"
      })
  void missingNullEmptyOrBlankFieldsAreBadRequestsNeverServerErrors(final String json) {
    webTestClient
        .post()
        .uri("/provider-connection-tests")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-1004");
    verifyNoInteractions(testProviderConnectionUseCase);
  }

  @Test
  void anUnknownChannelIsABadRequestWithItsOwnCode() {
    when(testProviderConnectionUseCase.test(any(), any()))
        .thenReturn(
            Mono.error(
                new co.edu.uco.notification.core.exception.ChannelNotAvailableException(
                    ChannelType.of("FAX"))));

    post(TestTokens.bearer("tenant-1"), Map.of("channel", "FAX", "providerId", "brevo"))
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-1003");
  }

  @Test
  void aFailingMeterRegistryOrLoggerDoesNotTurnAFinishedTestIntoAServerError() {
    when(testProviderConnectionUseCase.test(any(), any())).thenReturn(Mono.just(result(null)));
    org.mockito.Mockito.doThrow(new IllegalStateException("registry down"))
        .when(meterRegistry)
        .counter(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<String>any());

    post(TestTokens.bearer("tenant-1"), Map.of("channel", "EMAIL", "providerId", "brevo"))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("OK");
  }

  @Test
  void aBlankProviderIsABadRequestWithoutProbing() {
    post(TestTokens.bearer("tenant-1"), Map.of("channel", "EMAIL", "providerId", " "))
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-1004");
    verifyNoInteractions(testProviderConnectionUseCase);
  }

  @Test
  void twoTenantsGetTheSameResultAndEachLogLineCarriesOnlyItsOwnTenant() {
    when(testProviderConnectionUseCase.test(any(), any())).thenReturn(Mono.just(result(null)));

    final String first =
        post(TestTokens.bearer("tenant-1"), Map.of("channel", "EMAIL", "providerId", "brevo"))
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    final String firstLogs = probeLogs();
    logs.list.clear();
    final String second =
        post(TestTokens.bearer("tenant-2"), Map.of("channel", "EMAIL", "providerId", "brevo"))
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    final String secondLogs = probeLogs();

    assertEquals(first, second);
    assertTrue(firstLogs.contains("tenant-1") && !firstLogs.contains("tenant-2"), firstLogs);
    assertTrue(secondLogs.contains("tenant-2") && !secondLogs.contains("tenant-1"), secondLogs);
  }
}
