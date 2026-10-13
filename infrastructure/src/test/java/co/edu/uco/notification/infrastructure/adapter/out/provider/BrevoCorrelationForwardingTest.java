package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.infrastructure.config.BrevoProviderProperties;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.utils.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class BrevoCorrelationForwardingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AttachmentContentLoader LOADER =
      new AttachmentContentLoader(org.mockito.Mockito.mock(AttachmentStoragePort.class));

  private static FakeProviderServer fakeServer;
  private static WebClient webClient;

  private ListAppender<ILoggingEvent> logAppender;

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
  void setUp() {
    fakeServer.reset();
    logAppender = new ListAppender<>();
    logAppender.start();
    rootLogger().addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(logAppender);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private static BrevoNotificationProvider provider() {
    return new BrevoNotificationProvider(
        webClient,
        new BrevoProviderProperties(
            "test-api-key",
            "sender@example.com",
            "Notification UCO",
            fakeServer.baseUrl(),
            10_000L,
            5_000L),
        LOADER);
  }

  private static Notification emailWith(final CorrelationId correlationId) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-" + UUID.randomUUID()),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Subject", "Body"), Priority.NORMAL),
        correlationId);
  }

  private static JsonNode lastBody() throws Exception {
    return MAPPER.readTree(fakeServer.requests().get(fakeServer.requests().size() - 1).body());
  }

  private String logs() {
    return logAppender.list.stream().map(LogLines::render).collect(Collectors.joining("\n"));
  }

  @Test
  void theRequestCarriesThePersistedCorrelationIdAsTheOnlyTag() throws Exception {
    final CorrelationId correlationId = CorrelationId.of("corr-brevo-001");

    StepVerifier.create(provider().send(emailWith(correlationId)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final JsonNode tags = lastBody().get("tags");
    assertEquals(1, tags.size());
    assertEquals("corr-brevo-001", tags.get(0).asText());
  }

  @Test
  void theCorrelationIdNeverTravelsInTheEmailHeadersOrContent() throws Exception {
    StepVerifier.create(provider().send(emailWith(CorrelationId.of("corr-brevo-002"))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final JsonNode body = lastBody();
    assertFalse(body.get("headers").toString().contains("corr-brevo-002"));
    assertFalse(body.get("subject").asText().contains("corr-brevo-002"));
    assertFalse(body.get("textContent").asText().contains("corr-brevo-002"));
  }

  @Test
  void theIdempotencyKeyHeaderIsKeptNextToTheTag() throws Exception {
    final Notification notification = emailWith(CorrelationId.of("corr-brevo-003"));

    StepVerifier.create(provider().send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(
        notification.notificationId().value(),
        lastBody().get("headers").get("Idempotency-Key").asText());
  }

  @Test
  void aNotificationWithoutCorrelationIdSendsNoTagsField() throws Exception {
    StepVerifier.create(provider().send(emailWith(null)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertNull(lastBody().get("tags"));
  }

  @Test
  void aRetryOfTheSameNotificationResendsTheSameTag() throws Exception {
    final Notification notification = emailWith(CorrelationId.of("corr-brevo-004"));
    final BrevoNotificationProvider provider = provider();

    StepVerifier.create(provider.send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
    StepVerifier.create(provider.send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(2, fakeServer.requests().size());
    assertEquals(
        MAPPER.readTree(fakeServer.requests().get(0).body()).get("tags"),
        MAPPER.readTree(fakeServer.requests().get(1).body()).get("tags"));
  }

  @Test
  void theAcceptedAttemptIsLoggedWithTheCorrelationIdAndTheProviderMessageId() {
    fakeServer.nextResponse(201, "{\"messageId\":\"<202610070001.abc@smtp-relay.mailin.fr>\"}");

    StepVerifier.create(provider().send(emailWith(CorrelationId.of("corr-brevo-005"))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final String line =
        logAppender.list.stream()
            .map(LogLines::render)
            .filter(rendered -> rendered.contains("Notification dispatched"))
            .findFirst()
            .orElseThrow();
    assertTrue(line.contains("corr-brevo-005"));
    assertTrue(line.contains("202610070001.abc@smtp-relay.mailin.fr"));
    assertFalse(logs().contains("alice@example.com"));
  }

  @Test
  void anUnreadableSuccessBodyStillReturnsAccepted() {
    fakeServer.nextResponse(201, "not json");

    StepVerifier.create(provider().send(emailWith(CorrelationId.of("corr-brevo-006"))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
  }
}
