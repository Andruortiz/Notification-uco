package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.port.in.RegisterRecipientBounceUseCase;
import co.edu.uco.notification.infrastructure.config.SimulatedProviderProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class BrevoBounceWebhookControllerTest {

  private static final String SECRET = "unit-secret";

  private final RegisterRecipientBounceUseCase register =
      mock(RegisterRecipientBounceUseCase.class);

  @BeforeEach
  void setUp() {
    when(register.registerHardBounce(any(), any())).thenReturn(Mono.just(true));
  }

  private WebTestClient clientWith(final boolean simulatedEnabled) {
    return WebTestClient.bindToController(
            new BrevoBounceWebhookController(
                register, new SimulatedProviderProperties(null, simulatedEnabled), SECRET))
        .controllerAdvice(new NotificationExceptionHandler())
        .build();
  }

  private WebTestClient.ResponseSpec post(
      final WebTestClient client, final String secret, final Object body) {
    final WebTestClient.RequestBodySpec request =
        client.post().uri("/providers/brevo/bounce-events").contentType(MediaType.APPLICATION_JSON);
    if (secret != null) {
      request.header(BrevoBounceWebhookController.SECRET_HEADER, secret);
    }
    return request.bodyValue(body).exchange();
  }

  @Test
  void aMissingOrWrongSecretIsRejectedWithoutReadingAnOversizedMalformedBody() {
    final String huge = "{\"event\":\"hard_bounce\",\"junk\":\"" + "x".repeat(2_000_000);

    post(clientWith(false), null, huge).expectStatus().isUnauthorized();
    post(clientWith(false), "wrong", huge).expectStatus().isUnauthorized();

    verify(register, never()).registerHardBounce(any(), any());
  }

  @Test
  void theSimulatedMessageIdPrefixIsResolvedOnlyWhenTheSimulatedProviderIsEnabled() {
    final Map<String, String> event = Map.of("event", "hard_bounce", "message-id", "simulated-n-1");

    post(clientWith(true), SECRET, event).expectStatus().isOk();
    verify(register).registerHardBounce("simulated-n-1", "hard_bounce");

    post(clientWith(false), SECRET, event).expectStatus().isOk();
    verify(register).registerHardBounce(isNull(), any());
  }

  @Test
  void aRealMessageIdIsForwardedRegardlessOfTheSimulatedFlag() {
    post(clientWith(false), SECRET, Map.of("event", "blocked", "message-id", "<m@smtp>"))
        .expectStatus()
        .isOk();

    verify(register).registerHardBounce("<m@smtp>", "blocked");
  }
}
