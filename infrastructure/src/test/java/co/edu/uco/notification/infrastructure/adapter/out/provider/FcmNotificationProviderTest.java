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
import co.edu.uco.notification.infrastructure.config.FcmCredentials;
import co.edu.uco.notification.infrastructure.config.FcmProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

class FcmNotificationProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FcmTestCredentials CREDENTIALS = FcmTestCredentials.shared();
  private static final String ACCESS_TOKEN = "test-access-" + "token-1";
  private static final String RECIPIENT = "device-" + "token-demo-1234";
  private static final String TITLE = "TituloSecretoPush";
  private static final String BODY = "CuerpoSecretoPush";
  private static final String SEND_PATH =
      "/v1/projects/" + FcmTestCredentials.PROJECT_ID + "/messages:send";

  private static FakeProviderServer authServer;
  private static FakeProviderServer fcmServer;
  private static WebClient webClient;

  private ListAppender<ILoggingEvent> logAppender;

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
  void setUp() {
    authServer.reset();
    fcmServer.reset();
    authServer.nextResponse(200, "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":3600}");
    fcmServer.nextResponse(200, "{\"name\":\"projects/demo-project/messages/0:msg-0001\"}");
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

  private static Notification pushTo(final String recipient, final String subject) {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("push-" + UUID.randomUUID()),
            ChannelType.of("PUSH"),
            RecipientId.of("recipient-1"),
            Recipient.of(recipient)),
        new NotificationDetails(NotificationContent.of(subject, BODY), Priority.NORMAL));
  }

  private static FcmProviderProperties properties(final String json, final String file) {
    return new FcmProviderProperties(
        json, file, fcmServer.baseUrl(), authServer.baseUrl() + "/token", 10_000L, 5_000L);
  }

  private static FcmNotificationProvider provider(
      final WebClient client, final FcmProviderProperties properties) {
    return new FcmNotificationProvider(client, properties, FcmCredentials.load(properties));
  }

  private static FcmNotificationProvider enabledProvider() {
    return provider(webClient, properties(CREDENTIALS.json(), null));
  }

  private static JsonNode json(final String body) {
    try {
      return MAPPER.readTree(body);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String fcmError(final int code, final String status, final String errorCode) {
    return "{\"error\":{\"code\":"
        + code
        + ",\"message\":\"Requested entity was not found for "
        + RECIPIENT
        + "\",\"status\":\""
        + status
        + "\",\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\","
        + "\"errorCode\":\""
        + errorCode
        + "\"}]}}";
  }

  private String logs() {
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
  }

  private List<ILoggingEvent> warnings() {
    return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  private static String sentAssertion() {
    return authServer.requests().stream()
        .map(request -> request.body())
        .map(body -> URLDecoder.decode(body, StandardCharsets.UTF_8))
        .map(body -> body.substring(body.indexOf("assertion=") + "assertion=".length()))
        .findFirst()
        .orElse("no-assertion-sent");
  }

  private static void assertNoSecretsIn(final String text) {
    assertFalse(text.contains(CREDENTIALS.privateKeyFragment()), "must not contain the key");
    assertFalse(text.contains(FcmTestCredentials.CLIENT_EMAIL), "must not contain the email");
    assertFalse(text.contains(ACCESS_TOKEN), "must not contain the access token");
    assertFalse(text.contains(sentAssertion()), "must not contain the signed assertion");
    assertFalse(text.contains(RECIPIENT), "must not contain the full device token");
    assertFalse(text.contains(TITLE), "must not contain the push title");
    assertFalse(text.contains(BODY), "must not contain the push body");
  }

  @Test
  void sendRejectsNullNotification() {
    final FcmNotificationProvider provider = enabledProvider();

    assertThrows(NullPointerException.class, () -> provider.send(null));
  }

  @Test
  void declaresTheFcmProviderId() {
    assertEquals(ProviderId.of("fcm"), enabledProvider().providerId());
  }

  @Test
  void happyPathSendsTheNotificationWithBearerAuthorizationAndReturnsAccepted() {
    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, authServer.requests().size());
    assertEquals(1, fcmServer.requests().size());
    final FakeProviderServer.RecordedRequest request = fcmServer.requests().get(0);
    assertEquals(SEND_PATH, request.path());
    assertEquals("Bearer " + ACCESS_TOKEN, request.header("Authorization"));
    assertTrue(request.header("Content-Type").startsWith("application/json"));
    final JsonNode message = json(request.body()).get("message");
    assertEquals(List.of("token", "notification"), fieldNames(message));
    assertEquals(RECIPIENT, message.get("token").asText());
    assertEquals(TITLE, message.get("notification").get("title").asText());
    assertEquals(BODY, message.get("notification").get("body").asText());
  }

  private static List<String> fieldNames(final JsonNode node) {
    final List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  @Test
  void consecutiveSendsReuseTheAuthorization() {
    final FcmNotificationProvider provider = enabledProvider();

    StepVerifier.create(provider.send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
    StepVerifier.create(provider.send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, authServer.requests().size());
    assertEquals(2, fcmServer.requests().size());
  }

  @Test
  void enabledConstructionEmitsNoWarning() {
    enabledProvider();

    assertTrue(warnings().isEmpty());
  }

  static Stream<Arguments> disabledConfigurations() {
    return Stream.of(
        Arguments.of(null, null, "FCM_CREDENTIALS_JSON"),
        Arguments.of(" ", "", "FCM_CREDENTIALS_FILE"),
        Arguments.of(CREDENTIALS.json(), "/run/secrets/fcm.json", "ambiguous"),
        Arguments.of(CREDENTIALS.jsonWithout("client_email"), null, "client_email"),
        Arguments.of(
            CREDENTIALS.jsonWith(FcmTestCredentials.PRIVATE_KEY_FIELD, "not-a-key"),
            null,
            "PKCS#8"),
        Arguments.of(null, "/definitely/absent/fcm.json", "unreadable"));
  }

  @ParameterizedTest
  @MethodSource("disabledConfigurations")
  void disabledProviderFailsBeforeAnyCallNamingTheReason(
      final String json, final String file, final String expectedDetail) {
    final FcmNotificationProvider provider = provider(webClient, properties(json, file));

    StepVerifier.create(provider.send(pushTo(RECIPIENT, TITLE)))
        .expectErrorSatisfies(
            error -> {
              assertTrue(error instanceof ProviderDisabledException);
              assertTrue(error.getMessage().contains("fcm"));
              assertTrue(error.getMessage().contains(expectedDetail), error.getMessage());
              assertNoSecretsIn(error.getMessage());
            })
        .verify();

    assertTrue(authServer.requests().isEmpty());
    assertTrue(fcmServer.requests().isEmpty());
  }

  @Test
  void disabledConstructionLogsExactlyOneWarningWithoutCredentialFragments() {
    provider(webClient, properties(CREDENTIALS.jsonWith("type", "authorized_user"), null));

    assertEquals(1, warnings().size());
    final String message = warnings().get(0).getFormattedMessage();
    assertTrue(message.contains("providerId=fcm"));
    assertTrue(message.contains("FCM_CREDENTIALS_JSON"));
    assertNoSecretsIn(message);
  }

  static Stream<Arguments> providerResponses() {
    return Stream.of(
        Arguments.of(
            404, fcmError(404, "NOT_FOUND", "UNREGISTERED"), AttemptResult.PERMANENT_FAILURE),
        Arguments.of(
            400,
            fcmError(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT"),
            AttemptResult.PERMANENT_FAILURE),
        Arguments.of(
            403,
            fcmError(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH"),
            AttemptResult.PERMANENT_FAILURE),
        Arguments.of(
            401,
            fcmError(401, "UNAUTHENTICATED", "THIRD_PARTY_AUTH_ERROR"),
            AttemptResult.PERMANENT_FAILURE),
        Arguments.of(
            429,
            fcmError(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED"),
            AttemptResult.RECOVERABLE_FAILURE),
        Arguments.of(500, fcmError(500, "INTERNAL", "INTERNAL"), AttemptResult.RECOVERABLE_FAILURE),
        Arguments.of(
            503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE"), AttemptResult.RECOVERABLE_FAILURE),
        Arguments.of(503, "", AttemptResult.RECOVERABLE_FAILURE));
  }

  @ParameterizedTest
  @MethodSource("providerResponses")
  void classifiesEachProviderResponseByItsHttpStatus(
      final int status, final String body, final AttemptResult expected) {
    fcmServer.nextResponse(status, body);

    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, TITLE)))
        .expectNext(expected)
        .verifyComplete();

    assertEquals(1, fcmServer.requests().size());
  }

  @Test
  void anUnauthorizedSendInvalidatesTheAuthorizationForTheNextSend() {
    final FcmNotificationProvider provider = enabledProvider();
    fcmServer.nextResponse(401, fcmError(401, "UNAUTHENTICATED", "UNSPECIFIED_ERROR"));

    StepVerifier.create(provider.send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    fcmServer.nextResponse(200, "{\"name\":\"projects/demo-project/messages/0:msg-0002\"}");
    StepVerifier.create(provider.send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(2, authServer.requests().size());
  }

  static Stream<Arguments> authorizationResponses() {
    return Stream.of(
        Arguments.of(400, AttemptResult.PERMANENT_FAILURE),
        Arguments.of(401, AttemptResult.PERMANENT_FAILURE),
        Arguments.of(429, AttemptResult.RECOVERABLE_FAILURE),
        Arguments.of(503, AttemptResult.RECOVERABLE_FAILURE));
  }

  @ParameterizedTest
  @MethodSource("authorizationResponses")
  void aFailedAuthorizationIsClassifiedAndNeverReachesTheSendEndpoint(
      final int status, final AttemptResult expected) {
    authServer.nextResponse(
        status,
        "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid grant for "
            + FcmTestCredentials.CLIENT_EMAIL
            + "\"}");

    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, TITLE)))
        .expectNext(expected)
        .verifyComplete();

    assertTrue(fcmServer.requests().isEmpty());
    final String logs = logs();
    assertTrue(logs.contains("stage=authorization"));
    assertTrue(logs.contains("httpStatus=" + status));
    assertFalse(logs.contains("Invalid grant"));
    assertNoSecretsIn(logs);
  }

  @Test
  void anUnreachableAuthorizationServiceIsRecoverable() {
    final FakeProviderServer closed = FakeProviderServer.start();
    final String closedUrl = closed.baseUrl();
    closed.stop();
    final FcmProviderProperties properties =
        new FcmProviderProperties(
            CREDENTIALS.json(), null, fcmServer.baseUrl(), closedUrl + "/token", 10_000L, 5_000L);

    StepVerifier.create(provider(webClient, properties).send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();

    assertTrue(fcmServer.requests().isEmpty());
    final String logs = logs();
    assertTrue(logs.contains("stage=authorization"));
    assertTrue(logs.contains("errorType="));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "[]"})
  void anUnreadableSuccessBodyDoesNotChangeTheCategory(final String body) {
    fcmServer.nextResponse(200, body);

    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "{\"error\":{\"status\":\"NOT_FOUND\"}}"})
  void anUnreadableOrIncompleteRejectionBodyDoesNotChangeTheCategory(final String body) {
    fcmServer.nextResponse(404, body);

    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();
  }

  @Test
  void connectionFailureIsRecoverableAndLogsNoErrorMessage() {
    final FakeProviderServer closed = FakeProviderServer.start();
    final String closedUrl = closed.baseUrl();
    closed.stop();
    final FcmProviderProperties properties =
        new FcmProviderProperties(
            CREDENTIALS.json(), null, closedUrl, authServer.baseUrl() + "/token", 10_000L, 5_000L);

    StepVerifier.create(
            provider(WebClient.builder().baseUrl(closedUrl).build(), properties)
                .send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("errorType="));
    assertFalse(logs.contains(closedUrl));
    assertNoSecretsIn(logs);
  }

  @Test
  void sendTimesOutWithinTheConfiguredDuration() {
    final WebClient shortTimeoutClient =
        WebClient.builder()
            .baseUrl(fcmServer.baseUrl())
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(Duration.ofMillis(500))))
            .build();
    final FcmNotificationProvider provider =
        provider(shortTimeoutClient, properties(CREDENTIALS.json(), null));
    StepVerifier.create(provider.send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
    fcmServer.nextDelay(3_000);

    final Instant start = Instant.now();
    StepVerifier.create(provider.send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();
    final Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "elapsed was " + elapsed);
    assertEquals(2, fcmServer.requests().size());
  }

  @Test
  void aNotificationWithoutSubjectIsSentWithoutTitle() {
    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, null)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final JsonNode notification =
        json(fcmServer.requests().get(0).body()).get("message").get("notification");
    assertEquals(List.of("body"), fieldNames(notification));
    assertEquals(BODY, notification.get("body").asText());
  }

  static Stream<String> opaqueRecipients() {
    return Stream.of(
        "alice@example.com",
        "+573001234567",
        "fDemo" + "x".repeat(170) + ":zz",
        "token with spaces and ñ");
  }

  @ParameterizedTest
  @MethodSource("opaqueRecipients")
  void theRecipientIsSentUnchangedAndUnvalidated(final String recipient) {
    StepVerifier.create(enabledProvider().send(pushTo(recipient, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fcmServer.requests().size());
    assertEquals(
        recipient, json(fcmServer.requests().get(0).body()).get("message").get("token").asText());
  }

  @Test
  void acceptedDispatchLogsTheMessageIdAndTheMaskedRecipientOnly() {
    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("providerMessageId=0:msg-0001"));
    assertTrue(logs.contains("recipient=***1234"));
    assertTrue(logs.contains("tenantId=tenant-1"));
    assertNoSecretsIn(logs);
  }

  @Test
  void rejectedDispatchLogsTheProviderErrorCodeButNeverItsMessage() {
    fcmServer.nextResponse(404, fcmError(404, "NOT_FOUND", "UNREGISTERED"));

    StepVerifier.create(enabledProvider().send(pushTo(RECIPIENT, TITLE)))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    final String logs = logs();
    assertTrue(logs.contains("providerErrorCode=UNREGISTERED"));
    assertTrue(logs.contains("httpStatus=404"));
    assertTrue(logs.contains("recipient=***1234"));
    assertFalse(logs.contains("Requested entity"));
    assertNoSecretsIn(logs);
  }

  @ParameterizedTest
  @ValueSource(strings = {"abcd", "a"})
  void aShortRecipientIsFullyMasked(final String recipient) {
    StepVerifier.create(enabledProvider().send(pushTo(recipient, TITLE)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertTrue(logs().lines().anyMatch(line -> line.endsWith("recipient=***")), logs());
  }
}
