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
import co.edu.uco.notification.infrastructure.config.TwilioProviderProperties;
import co.edu.uco.notification.utils.CorrelationId;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class TwilioCorrelationNotForwardedTest {

  private static final String CORRELATION_ID = "corr-twilio-001";

  private static FakeProviderServer fakeServer;
  private static WebClient webClient;

  @BeforeAll
  static void startServer() {
    fakeServer = FakeProviderServer.start();
    webClient = WebClient.builder().baseUrl(fakeServer.baseUrl()).build();
  }

  @AfterAll
  static void stopServer() {
    fakeServer.stop();
  }

  @BeforeEach
  void resetServer() {
    fakeServer.reset();
  }

  private static TwilioNotificationProvider provider() {
    return new TwilioNotificationProvider(
        webClient,
        new TwilioProviderProperties(
            "AC" + "0123456789abcdef0123456789abcdef",
            "unit-test-auth-token",
            "+15005550006",
            fakeServer.baseUrl(),
            10_000L,
            5_000L));
  }

  private static Notification smsWith(final CorrelationId correlationId) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("sms-" + UUID.randomUUID()),
            ChannelType.of("SMS"),
            RecipientId.of("recipient-1"),
            Recipient.of("+573001234567")),
        new NotificationDetails(NotificationContent.of("Asunto", "Hola"), Priority.NORMAL),
        correlationId);
  }

  @Test
  void theOutgoingRequestCarriesNeitherTheCorrelationIdNorItsHeaderName() {
    final Notification notification = smsWith(CorrelationId.of(CORRELATION_ID));
    assertNotNull(notification.correlationId());

    StepVerifier.create(provider().send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
    final FakeProviderServer.RecordedRequest request = fakeServer.requests().get(0);
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
  void aNotificationWithoutCorrelationIdIsSentTheSameWay() {
    StepVerifier.create(provider().send(smsWith(null)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
    assertFalse(fakeServer.requests().get(0).body().contains("corr-"));
  }
}
