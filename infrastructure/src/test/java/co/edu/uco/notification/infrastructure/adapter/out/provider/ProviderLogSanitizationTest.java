package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.infrastructure.config.BrevoProviderProperties;
import co.edu.uco.notification.infrastructure.config.LogCapture;
import co.edu.uco.notification.infrastructure.config.TwilioProviderProperties;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class ProviderLogSanitizationTest {

  private static final String CREDENTIAL_SENTINEL = "SENTINEL-CREDENTIAL-7f3a9c";
  private static final String CONTENT_SENTINEL = "SENTINEL-CONTENT-b21d04";
  private static final String PHONE = "+573001234567";
  private static final String EMAIL = "sentinel.person@example.com";

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

  private static Notification notification(
      final String channel, final String recipient, final String subject) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("ext-" + java.util.UUID.randomUUID()),
            ChannelType.of(channel),
            RecipientId.of("recipient-1"),
            Recipient.of(recipient)),
        new NotificationDetails(
            NotificationContent.of(subject, "Body " + CONTENT_SENTINEL), Priority.NORMAL));
  }

  private static void assertClean(final String logs, final List<String> forbidden) {
    forbidden.forEach(
        value -> assertFalse(logs.contains(value), "log output must not contain " + value));
  }

  @Test
  void twilioNeverLogsTheProviderResponseCredentialTheFullNumberOrTheContent() {
    final TwilioNotificationProvider provider =
        new TwilioNotificationProvider(
            webClient,
            new TwilioProviderProperties(
                "AC" + "0123456789abcdef0123456789abcdef",
                CREDENTIAL_SENTINEL,
                "+15005550006",
                fakeServer.baseUrl(),
                10_000L,
                5_000L));
    fakeServer.nextResponse(
        400,
        "{\"code\":21211,\"message\":\"token="
            + CREDENTIAL_SENTINEL
            + " to "
            + PHONE
            + " "
            + CONTENT_SENTINEL
            + "\"}");

    try (LogCapture capture = LogCapture.of(TwilioNotificationProvider.class)) {
      StepVerifier.create(provider.send(notification("SMS", PHONE, "subject")))
          .expectNextCount(1)
          .verifyComplete();

      final String logs = capture.rendered();
      assertTrue(logs.contains("providerErrorCode"));
      assertClean(logs, List.of(CREDENTIAL_SENTINEL, PHONE, CONTENT_SENTINEL));
    }
  }

  @Test
  void brevoNeverLogsTheApiKeyTheResponseBodyTheFullAddressOrTheContent() {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(
            webClient,
            new BrevoProviderProperties(
                CREDENTIAL_SENTINEL,
                "sender@example.com",
                "Notification UCO",
                fakeServer.baseUrl(),
                10_000L,
                5_000L),
            new AttachmentContentLoader(
                org.mockito.Mockito.mock(
                    co.edu.uco.notification.core.port.out.AttachmentStoragePort.class)));
    fakeServer.nextResponse(
        401,
        "{\"message\":\"api-key "
            + CREDENTIAL_SENTINEL
            + " rejected for "
            + EMAIL
            + " "
            + CONTENT_SENTINEL
            + "\"}");

    try (LogCapture capture = LogCapture.of(BrevoNotificationProvider.class)) {
      StepVerifier.create(provider.send(notification("EMAIL", EMAIL, "subject")))
          .expectNextCount(1)
          .verifyComplete();

      assertClean(capture.rendered(), List.of(CREDENTIAL_SENTINEL, EMAIL, CONTENT_SENTINEL));
    }
  }

  @Test
  void brevoRejectionBeforeTheCallDoesNotLogTheAddressOrTheContent() {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(
            webClient,
            new BrevoProviderProperties(
                CREDENTIAL_SENTINEL,
                "sender@example.com",
                "Notification UCO",
                fakeServer.baseUrl(),
                10_000L,
                5_000L),
            new AttachmentContentLoader(
                org.mockito.Mockito.mock(
                    co.edu.uco.notification.core.port.out.AttachmentStoragePort.class)));

    try (LogCapture capture = LogCapture.of(BrevoNotificationProvider.class)) {
      StepVerifier.create(provider.send(notification("EMAIL", EMAIL, " ")))
          .expectNextCount(1)
          .verifyComplete();

      assertTrue(capture.rendered().contains("missing-subject"));
      assertClean(capture.rendered(), List.of(CREDENTIAL_SENTINEL, EMAIL, CONTENT_SENTINEL));
    }
  }
}
