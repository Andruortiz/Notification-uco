package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.port.in.ConfigurationDescription;
import co.edu.uco.notification.core.port.in.ParameterDescription;
import co.edu.uco.notification.core.port.in.QueryConfigurationUseCase;
import co.edu.uco.notification.core.port.out.SubscriptionTicketPort;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticatedPrincipalArgumentResolver;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticationWebFilter;
import co.edu.uco.notification.infrastructure.adapter.in.web.RouteAuthorizationPolicy;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.config.SecurityConfig;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = ConfigurationController.class)
@Import({
  SecurityConfig.class,
  AuthenticationWebFilter.class,
  LocalJwtTokenValidationAdapter.class,
  RouteAuthorizationPolicy.class,
  AuthenticatedPrincipalArgumentResolver.class
})
class ConfigurationControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockBean private QueryConfigurationUseCase queryConfigurationUseCase;

  @MockBean private SubscriptionTicketPort subscriptionTicketPort;

  @Test
  void administradorReceivesTheConfigurationWithEveryField() {
    when(queryConfigurationUseCase.describe())
        .thenReturn(
            Mono.just(
                new ConfigurationDescription(
                    3,
                    ConfigurationSource.PARAMETERS,
                    Instant.parse("2026-10-05T11:00:00Z"),
                    Set.of("requeue.interval-ms"),
                    List.of(
                        new ParameterDescription(
                            ParameterDescriptor.forProvider(
                                "provider.brevo.timeout-ms", "brevo", 10_000, 1_000, 60_000),
                            12_000)))));

    webTestClient
        .get()
        .uri("/configuration")
        .header("Authorization", TestTokens.bearer("tenant-1", Role.ADMINISTRADOR))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json(
            """
            {"version":3,"source":"PARAMETERS","adoptedAt":"2026-10-05T11:00:00Z",
             "pendingRestart":["requeue.interval-ms"],
             "parameters":[{"key":"provider.brevo.timeout-ms","type":"INTEGER",
               "defaultValue":10000,"currentValue":12000,"min":1000,"max":60000,
               "scope":"PROVIDER","scopeIds":["brevo"],"adoption":"HOT"}]}
            """,
            true);
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"OPERADOR", "CLIENTE"})
  void rolesBelowAdministradorAreForbiddenWithoutQueryingTheConfiguration(final Role role) {
    webTestClient
        .get()
        .uri("/configuration")
        .header("Authorization", TestTokens.bearer("tenant-1", role))
        .exchange()
        .expectStatus()
        .isForbidden();

    verifyNoInteractions(queryConfigurationUseCase);
  }

  @Test
  void rejectsARequestWithoutATokenWithoutQueryingTheConfiguration() {
    webTestClient.get().uri("/configuration").exchange().expectStatus().isUnauthorized();

    verifyNoInteractions(queryConfigurationUseCase);
  }
}
