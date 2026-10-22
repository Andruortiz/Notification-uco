package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
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
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.ProviderTemplateReference;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.config.TwilioWhatsAppProviderProperties;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import io.netty.channel.ChannelOption;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class TwilioWhatsAppNotificationProviderTest {

  private static final String ACCOUNT_SID = "AC" + "0123456789abcdef0123456789abcdef";
  private static final String AUTH_TOKEN = "unit-test-auth-token";
  private static final String FROM_NUMBER = "+14155238886";
  private static final String RECIPIENT = "+573001234567";
  private static final String BODY = "Hola desde el canal WhatsApp";
  private static final String TEMPLATE_ID = "HX" + "0123456789abcdef0123456789abcdef";
  private static final String VARIABLE_VALUE = "Alice-variable-value";
  private static final String SEND_PATH = "/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json";

  private static FakeProviderServer fakeServer;
  private static WebClient webClient;

  private ListAppender<ILoggingEvent> logAppender;
  private Level previousRootLevel;

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
    logAppender = new ConcurrentListAppender();
    logAppender.start();
    previousRootLevel = rootLogger().getLevel();
    rootLogger().setLevel(Level.INFO);
    rootLogger().addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    rootLogger().setLevel(previousRootLevel);
    rootLogger().detachAppender(logAppender);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private static Notification whatsappTo(
      final String recipient, final NotificationContent content) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("wa-" + UUID.randomUUID()),
            ChannelType.of("WHATSAPP"),
            RecipientId.of("recipient-1"),
            Recipient.of(recipient)),
        new NotificationDetails(content, Priority.NORMAL));
  }

  private static Notification textTo(final String recipient, final String subject) {
    return whatsappTo(recipient, NotificationContent.of(subject, BODY));
  }

  private static Notification templateTo(final String recipient, final String templateId) {
    return whatsappTo(
        recipient,
        NotificationContent.ofProviderTemplate(
            null, ProviderTemplateReference.of(templateId, Map.of("1", VARIABLE_VALUE))));
  }

  private static TwilioWhatsAppProviderProperties properties(
      final String accountSid, final String authToken, final String fromNumber) {
    return new TwilioWhatsAppProviderProperties(
        accountSid, authToken, fromNumber, fakeServer.baseUrl(), 10_000L, 5_000L);
  }

  private static TwilioWhatsAppProviderProperties enabledProperties() {
    return properties(ACCOUNT_SID, AUTH_TOKEN, FROM_NUMBER);
  }

  private static TwilioWhatsAppNotificationProvider enabledProvider() {
    return new TwilioWhatsAppNotificationProvider(webClient, enabledProperties());
  }

  private static Map<String, String> form(final String body) {
    return Arrays.stream(body.split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
  }

  private String logs() {
    return logAppender.list.stream().map(LogLines::render).collect(Collectors.joining("\n"));
  }

  private List<ILoggingEvent> warnings() {
    return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  private static String basicCredentials() {
    return Base64.getEncoder()
        .encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.ISO_8859_1));
  }

  private void assertNoSecretsIn(final String text) {
    assertFalse(text.contains(ACCOUNT_SID), "must not contain the account sid");
    assertFalse(text.contains(AUTH_TOKEN), "must not contain the auth token");
    assertFalse(text.contains(basicCredentials()), "must not contain the basic credentials");
    assertFalse(text.contains(RECIPIENT), "must not contain the full recipient number");
    assertFalse(text.contains(BODY), "must not contain the message body");
    assertFalse(text.contains(VARIABLE_VALUE), "must not contain a template variable value");
    assertFalse(text.contains(SEND_PATH), "must not contain the request url");
  }

  @Test
  void sendRejectsNullNotification() {
    final TwilioWhatsAppNotificationProvider provider = enabledProvider();

    assertThrows(NullPointerException.class, () -> provider.send(null));
  }

  @Test
  void declaresTheTwilioWhatsAppProviderId() {
    assertEquals(ProviderId.of("twilio-whatsapp"), enabledProvider().providerId());
  }

  @Test
  void exposesNoDisabledReasonWhenCredentialsAreComplete() {
    assertTrue(enabledProvider().disabledReason().isEmpty());
  }

  @Test
  void doesNotSupportAttachments() {
    assertFalse(enabledProvider().supportsAttachments());
  }

  @Test
  void freeTextIsSentWithBasicAuthAndTheWhatsAppPrefixesAndReturnsAccepted() {
    fakeServer.nextResponse(201, "{\"sid\":\"SM0001\",\"status\":\"queued\"}");

    StepVerifier.create(enabledProvider().send(textTo(RECIPIENT, "Asunto ignorado")))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
    final FakeProviderServer.RecordedRequest request = fakeServer.requests().get(0);
    assertEquals(SEND_PATH, request.path());
    assertEquals("Basic " + basicCredentials(), request.header("Authorization"));
    assertTrue(request.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
    assertEquals(
        Map.of("To", "whatsapp:" + RECIPIENT, "From", "whatsapp:" + FROM_NUMBER, "Body", BODY),
        form(request.body()));
    assertFalse(request.body().contains("Asunto"));
  }

  @Test
  void aProviderTemplateIsSentAsContentSidAndVariablesWithoutBody() {
    fakeServer.nextResponse(201, "{\"sid\":\"SM0002\",\"status\":\"queued\"}");

    StepVerifier.create(enabledProvider().send(templateTo(RECIPIENT, TEMPLATE_ID)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final Map<String, String> form = form(fakeServer.requests().get(0).body());
    assertEquals(TEMPLATE_ID, form.get("ContentSid"));
    assertEquals("{\"1\":\"" + VARIABLE_VALUE + "\"}", form.get("ContentVariables"));
    assertFalse(form.containsKey("Body"));
    assertEquals("whatsapp:" + RECIPIENT, form.get("To"));
    assertEquals("whatsapp:" + FROM_NUMBER, form.get("From"));
  }

  @Test
  void aTemplateIdWithoutTheContentSidShapeFailsPermanentlyWithoutCallingTheProvider() {
    StepVerifier.create(enabledProvider().send(templateTo(RECIPIENT, "not-a-content-sid")))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
    final String logs = logs();
    assertTrue(logs.contains("reason=invalid-provider-template-format"));
    assertFalse(logs.contains("not-a-content-sid"));
  }

  @Test
  void aProviderTemplateRejectedAsUnknownByTheProviderFailsPermanentlyWithItsCode() {
    fakeServer.nextResponse(404, "{\"code\":20404,\"message\":\"content not found\"}");

    StepVerifier.create(enabledProvider().send(templateTo(RECIPIENT, TEMPLATE_ID)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
    assertTrue(logs().contains("providerErrorCode=20404"));
  }

  @Test
  void freeTextOutsideTheSessionWindowFailsPermanentlyAndNamesTheReason() {
    fakeServer.nextResponse(
        400,
        "{\"code\":63016,\"message\":\"Failed to send freeform message to " + RECIPIENT + "\"}");

    StepVerifier.create(enabledProvider().send(textTo(RECIPIENT, null)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    final List<ILoggingEvent> warnings = warnings();
    assertEquals(1, warnings.size());
    final String message = LogLines.render(warnings.get(0));
    assertTrue(message.contains("reason=outside-session-window"));
    assertTrue(message.contains("providerErrorCode=63016"));
    assertTrue(message.contains("providerMessage=Failed to send freeform message to"));
    assertFalse(message.contains(RECIPIENT));
    assertNoSecretsIn(logs());
  }

  @Test
  void anUnknownRejectionCodeIsLoggedAsUnclassified() {
    fakeServer.nextResponse(400, "{\"code\":12345}");

    StepVerifier.create(enabledProvider().send(textTo(RECIPIENT, null)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(logs().contains("reason=unclassified"));
  }

  @Test
  void enabledConstructionEmitsNoWarning() {
    enabledProvider();

    assertTrue(warnings().isEmpty());
  }

  static Stream<Arguments> disabledConfigurations() {
    return Stream.of(
        Arguments.of(null, AUTH_TOKEN, FROM_NUMBER, "TWILIO_ACCOUNT_SID"),
        Arguments.of(" ", AUTH_TOKEN, FROM_NUMBER, "TWILIO_ACCOUNT_SID"),
        Arguments.of(ACCOUNT_SID, null, FROM_NUMBER, "TWILIO_AUTH_TOKEN"),
        Arguments.of(ACCOUNT_SID, "", FROM_NUMBER, "TWILIO_AUTH_TOKEN"),
        Arguments.of(ACCOUNT_SID, AUTH_TOKEN, null, "TWILIO_WHATSAPP_FROM_NUMBER"),
        Arguments.of("AC-not-hex", AUTH_TOKEN, FROM_NUMBER, "TWILIO_ACCOUNT_SID"),
        Arguments.of(ACCOUNT_SID, AUTH_TOKEN, "14155238886", "TWILIO_WHATSAPP_FROM_NUMBER"));
  }

  @ParameterizedTest
  @MethodSource("disabledConfigurations")
  void disabledProviderFailsBeforeAnyCallNamingTheMissingOrMalformedSetting(
      final String accountSid,
      final String authToken,
      final String fromNumber,
      final String expectedVariable) {
    final TwilioWhatsAppNotificationProvider provider =
        new TwilioWhatsAppNotificationProvider(
            webClient, properties(accountSid, authToken, fromNumber));

    StepVerifier.create(provider.send(textTo(RECIPIENT, null)))
        .expectErrorSatisfies(
            error -> {
              assertTrue(error instanceof ProviderDisabledException);
              assertTrue(error.getMessage().contains("twilio-whatsapp"));
              assertTrue(error.getMessage().contains(expectedVariable));
              assertNoSecretsIn(error.getMessage());
            })
        .verify();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void disabledConstructionLogsExactlyOneWarningWithoutSecretValues() {
    new TwilioWhatsAppNotificationProvider(
        webClient, properties(ACCOUNT_SID, null, "not-a-number"));

    assertEquals(1, warnings().size());
    final String message = LogLines.render(warnings().get(0));
    assertTrue(message.contains("providerId=twilio-whatsapp"));
    assertTrue(message.contains("TWILIO_AUTH_TOKEN"));
    assertFalse(message.contains(ACCOUNT_SID));
    assertFalse(message.contains("not-a-number"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"alice@example.com", "3001234567", "+0573001234567", "+57 300 1234567"})
  void recipientWithoutInternationalFormatFailsPermanentlyWithoutCallingTheProvider(
      final String recipient) {
    StepVerifier.create(enabledProvider().send(textTo(recipient, null)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
    final String logs = logs();
    assertTrue(logs.contains("reason=invalid-recipient-format"));
    assertFalse(logs.contains(recipient));
  }

  static Stream<Arguments> providerResponses() {
    return Stream.of(
        Arguments.of(400, "{\"code\":63024}", AttemptResult.PERMANENT_FAILURE),
        Arguments.of(401, "{\"code\":20003}", AttemptResult.PERMANENT_FAILURE),
        Arguments.of(404, "{\"code\":20404}", AttemptResult.PERMANENT_FAILURE),
        Arguments.of(429, "{\"code\":20429}", AttemptResult.RECOVERABLE_FAILURE),
        Arguments.of(500, "{\"code\":20500}", AttemptResult.RECOVERABLE_FAILURE),
        Arguments.of(503, "", AttemptResult.RECOVERABLE_FAILURE));
  }

  @ParameterizedTest
  @MethodSource("providerResponses")
  void classifiesEachProviderResponseByItsHttpStatus(
      final int status, final String body, final AttemptResult expected) {
    fakeServer.nextResponse(status, body);

    StepVerifier.create(enabledProvider().send(textTo(RECIPIENT, null)))
        .expectNext(expected)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "[]"})
  void anUnreadableSuccessBodyDoesNotChangeTheCategory(final String body) {
    fakeServer.nextResponse(201, body);

    StepVerifier.create(enabledProvider().send(textTo(RECIPIENT, null)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
  }

  @Test
  void anUnreadableRejectionBodyDoesNotChangeTheCategory() {
    fakeServer.nextResponse(400, "not json");

    StepVerifier.create(enabledProvider().send(textTo(RECIPIENT, null)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();
  }

  @Test
  void connectionFailureIsRecoverableAndLogsNeitherTheUrlNorTheErrorMessage() {
    final FakeProviderServer closed = FakeProviderServer.start();
    final String closedUrl = closed.baseUrl();
    closed.stop();
    final TwilioWhatsAppNotificationProvider provider =
        new TwilioWhatsAppNotificationProvider(
            WebClient.builder().baseUrl(closedUrl).build(),
            new TwilioWhatsAppProviderProperties(
                ACCOUNT_SID, AUTH_TOKEN, FROM_NUMBER, closedUrl, 10_000L, 5_000L));

    StepVerifier.create(provider.send(textTo(RECIPIENT, null)))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("errorType="));
    assertNoSecretsIn(logs);
  }

  @Test
  void requestTimesOutWithinTheConfiguredDuration() {
    final WebClient shortTimeoutClient =
        WebClient.builder()
            .baseUrl(fakeServer.baseUrl())
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(Duration.ofMillis(500))))
            .build();
    final TwilioWhatsAppNotificationProvider provider =
        new TwilioWhatsAppNotificationProvider(shortTimeoutClient, enabledProperties());
    fakeServer.nextDelay(3_000);

    final Instant start = Instant.now();
    StepVerifier.create(provider.send(textTo(RECIPIENT, null)))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();
    final Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "elapsed was " + elapsed);
    assertEquals(1, fakeServer.requests().size());
  }

  @Test
  void noRequestCarriesAnIdempotencyHeaderAndARetryResendsTheMessage() {
    final Notification notification = textTo(RECIPIENT, null);
    final TwilioWhatsAppNotificationProvider provider = enabledProvider();

    StepVerifier.create(provider.send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
    StepVerifier.create(provider.send(notification))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(2, fakeServer.requests().size());
    fakeServer
        .requests()
        .forEach(
            request ->
                assertTrue(
                    request.headers().keySet().stream()
                        .noneMatch(name -> name.toLowerCase().contains("idempotency")),
                    "no idempotency header expected"));
  }

  @Test
  void acceptedDispatchLogsTheProviderMessageIdAndTheMaskedNumberOnly() {
    fakeServer.nextResponse(201, "{\"sid\":\"SM0123456789\",\"status\":\"queued\"}");

    StepVerifier.create(enabledProvider().send(templateTo(RECIPIENT, TEMPLATE_ID)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("providerMessageId=SM0123456789"));
    assertTrue(logs.contains("recipient=***4567"));
    assertTrue(logs.contains("tenantId=tenant-1"));
    assertNoSecretsIn(logs);
  }

  @Test
  void rejectedDispatchLogsTheProviderErrorCodeAndItsMessageWithoutTheRecipient() {
    final String providerMessage = "The 'To' number " + RECIPIENT + " is not a valid phone number.";
    fakeServer.nextResponse(
        400,
        "{\"code\":63024,\"message\":\""
            + providerMessage
            + "\",\"more_info\":\"https://www.twilio.com/docs/errors/63024\",\"status\":400}");

    StepVerifier.create(enabledProvider().send(templateTo(RECIPIENT, TEMPLATE_ID)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("providerErrorCode=63024"));
    assertTrue(logs.contains("reason=invalid-recipient"));
    assertTrue(logs.contains("httpStatus=400"));
    assertTrue(logs.contains("recipient=***4567"));
    assertTrue(logs.contains("is not a valid phone number"));
    assertTrue(logs.contains("providerMoreInfo=https://www.twilio.com/docs/errors/63024"));
    assertFalse(logs.contains(RECIPIENT));
    assertNoSecretsIn(logs);
  }
}
