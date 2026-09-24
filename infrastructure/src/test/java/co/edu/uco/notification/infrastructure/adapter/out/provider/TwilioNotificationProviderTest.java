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
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.infrastructure.config.TwilioProviderProperties;
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

class TwilioNotificationProviderTest {

  private static final String ACCOUNT_SID = "AC" + "0123456789abcdef0123456789abcdef";
  private static final String AUTH_TOKEN = "unit-test-auth-token";
  private static final String FROM_NUMBER = "+15005550006";
  private static final String RECIPIENT = "+573001234567";
  private static final String SEND_PATH = "/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json";

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

  private static Notification smsTo(final String recipient, final String subject) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("sms-" + UUID.randomUUID()),
            ChannelType.of("SMS"),
            RecipientId.of("recipient-1"),
            Recipient.of(recipient)),
        new NotificationDetails(
            NotificationContent.of(subject, "Hola desde el canal SMS"), Priority.NORMAL));
  }

  private static TwilioProviderProperties properties(
      final String accountSid, final String authToken, final String fromNumber) {
    return new TwilioProviderProperties(
        accountSid, authToken, fromNumber, fakeServer.baseUrl(), 10_000L, 5_000L);
  }

  private static TwilioProviderProperties enabledProperties() {
    return properties(ACCOUNT_SID, AUTH_TOKEN, FROM_NUMBER);
  }

  private static TwilioNotificationProvider enabledProvider() {
    return new TwilioNotificationProvider(webClient, enabledProperties());
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
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
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
    assertFalse(text.contains("Hola desde el canal SMS"), "must not contain the sms body");
  }

  @Test
  void sendRejectsNullNotification() {
    final TwilioNotificationProvider provider = enabledProvider();

    assertThrows(NullPointerException.class, () -> provider.send(null));
  }

  @Test
  void declaresTheTwilioProviderId() {
    assertEquals(ProviderId.of("twilio"), enabledProvider().providerId());
  }

  @Test
  void happyPathSendsTheFormWithBasicAuthAndReturnsAccepted() {
    fakeServer.nextResponse(201, "{\"sid\":\"SM0001\",\"status\":\"queued\"}");

    StepVerifier.create(enabledProvider().send(smsTo(RECIPIENT, "Asunto ignorado")))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
    final FakeProviderServer.RecordedRequest request = fakeServer.requests().get(0);
    assertEquals(SEND_PATH, request.path());
    assertEquals("Basic " + basicCredentials(), request.header("Authorization"));
    assertTrue(request.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
    final Map<String, String> form = form(request.body());
    assertEquals(
        Map.of("To", RECIPIENT, "From", FROM_NUMBER, "Body", "Hola desde el canal SMS"), form);
    assertFalse(request.body().contains("Asunto"));
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
        Arguments.of(ACCOUNT_SID, AUTH_TOKEN, null, "TWILIO_FROM_NUMBER"),
        Arguments.of(
            "SK" + "0123456789abcdef0123456789abcdef",
            AUTH_TOKEN,
            FROM_NUMBER,
            "TWILIO_ACCOUNT_SID"),
        Arguments.of("AC-not-hex", AUTH_TOKEN, FROM_NUMBER, "TWILIO_ACCOUNT_SID"),
        Arguments.of(ACCOUNT_SID, AUTH_TOKEN, "5005550006", "TWILIO_FROM_NUMBER"));
  }

  @ParameterizedTest
  @MethodSource("disabledConfigurations")
  void disabledProviderFailsBeforeAnyCallNamingTheMissingOrMalformedSetting(
      final String accountSid,
      final String authToken,
      final String fromNumber,
      final String expectedVariable) {
    final TwilioNotificationProvider provider =
        new TwilioNotificationProvider(webClient, properties(accountSid, authToken, fromNumber));

    StepVerifier.create(provider.send(smsTo(RECIPIENT, null)))
        .expectErrorSatisfies(
            error -> {
              assertTrue(error instanceof ProviderDisabledException);
              assertTrue(error.getMessage().contains("twilio"));
              assertTrue(error.getMessage().contains(expectedVariable));
              assertNoSecretsIn(error.getMessage());
            })
        .verify();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void disabledConstructionLogsExactlyOneWarningWithoutSecretValues() {
    new TwilioNotificationProvider(webClient, properties(ACCOUNT_SID, null, "not-a-number"));

    assertEquals(1, warnings().size());
    final String message = warnings().get(0).getFormattedMessage();
    assertTrue(message.contains("providerId=twilio"));
    assertTrue(message.contains("TWILIO_AUTH_TOKEN"));
    assertFalse(message.contains(ACCOUNT_SID));
    assertFalse(message.contains("not-a-number"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"alice@example.com", "3001234567", "+0573001234567", "+57 300 1234567"})
  void recipientWithoutInternationalFormatFailsPermanentlyWithoutCallingTheProvider(
      final String recipient) {
    StepVerifier.create(enabledProvider().send(smsTo(recipient, null)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
    final String logs = logs();
    assertTrue(logs.contains("reason=invalid-recipient-format"));
    assertFalse(logs.contains(recipient));
  }

  static Stream<Arguments> providerResponses() {
    return Stream.of(
        Arguments.of(400, "{\"code\":21211}", AttemptResult.PERMANENT_FAILURE),
        Arguments.of(400, "{\"code\":21610}", AttemptResult.PERMANENT_FAILURE),
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

    StepVerifier.create(enabledProvider().send(smsTo(RECIPIENT, null)))
        .expectNext(expected)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "[]"})
  void anUnreadableSuccessBodyDoesNotChangeTheCategory(final String body) {
    fakeServer.nextResponse(201, body);

    StepVerifier.create(enabledProvider().send(smsTo(RECIPIENT, null)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
  }

  @Test
  void anUnreadableRejectionBodyDoesNotChangeTheCategory() {
    fakeServer.nextResponse(400, "not json");

    StepVerifier.create(enabledProvider().send(smsTo(RECIPIENT, null)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();
  }

  @Test
  void connectionFailureIsRecoverableAndLogsNeitherTheUrlNorTheErrorMessage() {
    final FakeProviderServer closed = FakeProviderServer.start();
    final String closedUrl = closed.baseUrl();
    closed.stop();
    final TwilioNotificationProvider provider =
        new TwilioNotificationProvider(
            WebClient.builder().baseUrl(closedUrl).build(),
            new TwilioProviderProperties(
                ACCOUNT_SID, AUTH_TOKEN, FROM_NUMBER, closedUrl, 10_000L, 5_000L));

    StepVerifier.create(provider.send(smsTo(RECIPIENT, null)))
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
    final TwilioNotificationProvider provider =
        new TwilioNotificationProvider(shortTimeoutClient, enabledProperties());
    fakeServer.nextDelay(3_000);

    final Instant start = Instant.now();
    StepVerifier.create(provider.send(smsTo(RECIPIENT, null)))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();
    final Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "elapsed was " + elapsed);
    assertEquals(1, fakeServer.requests().size());
  }

  @Test
  void acceptedDispatchLogsTheProviderMessageIdAndTheMaskedNumberOnly() {
    fakeServer.nextResponse(201, "{\"sid\":\"SM0123456789\",\"status\":\"queued\"}");

    StepVerifier.create(enabledProvider().send(smsTo(RECIPIENT, null)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("providerMessageId=SM0123456789"));
    assertTrue(logs.contains("recipient=***4567"));
    assertTrue(logs.contains("tenantId=tenant-1"));
    assertNoSecretsIn(logs);
  }

  @Test
  void rejectedDispatchLogsTheProviderErrorCodeButNeverItsMessage() {
    final String providerMessage = "The 'To' number " + RECIPIENT + " is not a valid phone number.";
    fakeServer.nextResponse(
        400, "{\"code\":21211,\"message\":\"" + providerMessage + "\",\"status\":400}");

    StepVerifier.create(enabledProvider().send(smsTo(RECIPIENT, null)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("providerErrorCode=21211"));
    assertTrue(logs.contains("httpStatus=400"));
    assertTrue(logs.contains("recipient=***4567"));
    assertFalse(logs.contains("is not a valid phone number"));
    assertNoSecretsIn(logs);
  }
}
