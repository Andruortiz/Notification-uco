package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.infrastructure.config.FcmCredentials;
import co.edu.uco.notification.infrastructure.config.FcmProviderProperties;
import co.edu.uco.notification.utils.CorrelationId;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class FcmCorrelationNotForwardedTest {

  private static final String CORRELATION_ID = "corr-fcm-001";
  private static final FcmTestCredentials CREDENTIALS = FcmTestCredentials.shared();

  private static FakeProviderServer authServer;
  private static FakeProviderServer fcmServer;
  private static WebClient webClient;

  @BeforeAll
  static void startServers() {
    authServer = FakeProviderServer.start();
    fcmServer = FakeProviderServer.start();
    webClient = WebClient.builder().baseUrl(fcmServer.baseUrl()).build();
  }

  @AfterAll
  static void stopServers() {
    authServer.stop();
    fcmServer.stop();
  }

  @BeforeEach
  void resetServers() {
    authServer.reset();
    fcmServer.reset();
    authServer.nextResponse(
        200, "{\"access_token\":\"unit-test-access-token\",\"expires_in\":3600}");
    fcmServer.nextResponse(200, "{\"name\":\"projects/demo-project/messages/0:msg-0001\"}");
  }

  private static FcmNotificationProvider provider() {
    final FcmProviderProperties properties =
        new FcmProviderProperties(
            CREDENTIALS.json(),
            null,
            fcmServer.baseUrl(),
            authServer.baseUrl() + "/token",
            10_000L,
            5_000L);
    return new FcmNotificationProvider(webClient, properties, FcmCredentials.load(properties));
  }

  private static Notification pushWith(final CorrelationId correlationId) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("push-" + UUID.randomUUID()),
            ChannelType.of("PUSH"),
            RecipientId.of("recipient-1"),
            Recipient.of("device-" + "token-demo-1234")),
        new NotificationDetails(NotificationContent.of("Titulo", "Cuerpo"), Priority.NORMAL),
        correlationId);
  }

  @Test
  void theOutgoingRequestCarriesNeitherTheCorrelationIdNorItsHeaderName() {
    final Notification notification = pushWith(CorrelationId.of(CORRELATION_ID));
    assertNotNull(notification.correlationId());

    StepVerifier.create(provider().send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fcmServer.requests().size());
    final FakeProviderServer.RecordedRequest request = fcmServer.requests().get(0);
    assertFalse(request.path().contains(CORRELATION_ID));
    assertFalse(request.body().contains(CORRELATION_ID));
    request
        .headers()
        .forEach(
            (name, values) -> {
              assertFalse(name.equalsIgnoreCase(CorrelationId.HEADER), name);
              assertFalse(values.toString().contains(CORRELATION_ID), name);
            });
  }

  @Test
  void theMessageHasNeitherDataNorAnalyticsLabelFields() {
    StepVerifier.create(provider().send(pushWith(CorrelationId.of(CORRELATION_ID))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final String body = fcmServer.requests().get(0).body();
    assertFalse(body.contains("\"data\""));
    assertFalse(body.contains("analytics_label"));
  }
}
