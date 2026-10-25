package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.ProbeOutcome;
import co.edu.uco.notification.infrastructure.config.BrevoProviderProperties;
import co.edu.uco.notification.infrastructure.config.FcmCredentials;
import co.edu.uco.notification.infrastructure.config.FcmProviderProperties;
import co.edu.uco.notification.infrastructure.config.SimulatedProviderProperties;
import co.edu.uco.notification.infrastructure.config.TwilioProviderProperties;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Base64;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class ProviderProbeAdaptersTest {

  private static final String SENTINEL = "sentinel-credential-9f3a7c";
  private static final String ACCOUNT_SID = "AC" + "0123456789abcdef0123456789abcdef";
  private static final String BREVO_BODY = "{\"message\":\"bad key " + SENTINEL + "\"}";

  private static FakeProviderServer server;
  private static FakeProviderServer authServer;
  private static WebClient client;

  private ListAppender<ILoggingEvent> logs;

  @BeforeAll
  static void start() {
    server = FakeProviderServer.start();
    authServer = FakeProviderServer.start();
    client = WebClient.builder().baseUrl(server.baseUrl()).build();
  }

  @AfterAll
  static void stop() {
    server.stop();
    authServer.stop();
  }

  @BeforeEach
  void setUp() {
    server.reset();
    authServer.reset();
    logs = new ConcurrentListAppender();
    logs.start();
    rootLogger().setLevel(Level.DEBUG);
    rootLogger().addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(logs);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private String loggedText() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining());
  }

  private static BrevoNotificationProvider brevo(final WebClient webClient) {
    return new BrevoNotificationProvider(
        webClient,
        new BrevoProviderProperties(
            SENTINEL, "sender@example.com", "UCO", server.baseUrl(), 10_000L, 5_000L),
        new AttachmentContentLoader(mock(AttachmentStoragePort.class)));
  }

  private static TwilioNotificationProvider twilio(final WebClient webClient) {
    return new TwilioNotificationProvider(
        webClient,
        new TwilioProviderProperties(
            ACCOUNT_SID, SENTINEL, "+15005550006", server.baseUrl(), 10_000L, 5_000L));
  }

  private static void assertFailed(final ProbeOutcome outcome, final ProbeErrorCode code) {
    assertFalse(outcome.isOk());
    assertEquals(code, outcome.errorCode());
    assertFalse(outcome.toString().contains(SENTINEL));
  }

  @Test
  void brevoProbeReadsTheAccountAndReportsOk() {
    server.nextResponse(200, "{\"email\":\"owner@example.com\"}");

    StepVerifier.create(brevo(client).probe())
        .assertNext(outcome -> assertTrue(outcome.isOk()))
        .verifyComplete();

    assertEquals(1, server.requests().size());
    assertEquals("/v3/account", server.requests().get(0).path());
    assertEquals(SENTINEL, server.requests().get(0).header("api-key"));
  }

  @Test
  void brevoProbeMapsRejectedCredentialsWithoutLeakingTheProviderBody() {
    server.nextResponse(401, BREVO_BODY);

    StepVerifier.create(brevo(client).probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.AUTH_REJECTED))
        .verifyComplete();

    assertFalse(loggedText().contains(SENTINEL));
  }

  @Test
  void brevoProbeMapsServerErrorsToProviderError() {
    server.nextResponse(500, BREVO_BODY);

    StepVerifier.create(brevo(client).probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.PROVIDER_ERROR))
        .verifyComplete();
  }

  @Test
  void brevoProbeMapsAClosedPortToUnreachable() {
    final FakeProviderServer closed = FakeProviderServer.start();
    final WebClient deadClient = WebClient.builder().baseUrl(closed.baseUrl()).build();
    closed.stop();

    StepVerifier.create(brevo(deadClient).probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.UNREACHABLE))
        .verifyComplete();
  }

  @Test
  void brevoProbeMapsALateAnswerToUnreachable() {
    server.nextDelay(1_500L);
    final WebClient impatient = clientWithResponseTimeout(server.baseUrl(), 300);

    StepVerifier.create(brevo(impatient).probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.UNREACHABLE))
        .verifyComplete();
  }

  @Test
  void aProviderWithoutCredentialsReportsMissingCredentialsWithoutCallingTheNetwork() {
    final BrevoNotificationProvider provider =
        new BrevoNotificationProvider(
            client,
            new BrevoProviderProperties(null, null, null, server.baseUrl(), null, null),
            new AttachmentContentLoader(mock(AttachmentStoragePort.class)));

    StepVerifier.create(provider.probe())
        .assertNext(
            outcome -> assertEquals(ProbeErrorCode.CREDENTIALS_MISSING, outcome.errorCode()))
        .verifyComplete();
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void twilioProbeReadsTheAccountWithBasicAuthAndReportsOk() {
    server.nextResponse(200, "{\"sid\":\"" + ACCOUNT_SID + "\"}");

    StepVerifier.create(twilio(client).probe())
        .assertNext(outcome -> assertTrue(outcome.isOk()))
        .verifyComplete();

    assertEquals("/2010-04-01/Accounts/" + ACCOUNT_SID + ".json", server.requests().get(0).path());
    final String expected =
        "Basic " + Base64.getEncoder().encodeToString((ACCOUNT_SID + ":" + SENTINEL).getBytes());
    assertEquals(expected, server.requests().get(0).header("Authorization"));
  }

  @Test
  void twilioProbeMapsRejectedCredentialsWithoutLeakingTheProviderBody() {
    server.nextResponse(401, "{\"code\":20003,\"message\":\"" + SENTINEL + "\"}");

    StepVerifier.create(twilio(client).probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.AUTH_REJECTED))
        .verifyComplete();

    assertFalse(loggedText().contains(SENTINEL));
  }

  @Test
  void twilioProbeMapsServerErrorsToProviderError() {
    server.nextResponse(503, "{}");

    StepVerifier.create(twilio(client).probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.PROVIDER_ERROR))
        .verifyComplete();
  }

  private static FcmNotificationProvider fcm() {
    final FcmProviderProperties properties =
        new FcmProviderProperties(
            FcmTestCredentials.shared().json(),
            null,
            server.baseUrl(),
            authServer.baseUrl() + "/token",
            10_000L,
            5_000L);
    return new FcmNotificationProvider(
        WebClient.builder().build(), properties, FcmCredentials.load(properties));
  }

  @Test
  void fcmProbeObtainsATokenAndReportsOk() {
    authServer.nextResponse(200, "{\"access_token\":\"tok\",\"expires_in\":3600}");

    StepVerifier.create(fcm().probe())
        .assertNext(outcome -> assertTrue(outcome.isOk()))
        .verifyComplete();

    assertEquals("/token", authServer.requests().get(0).path());
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void fcmProbeMapsARevokedCredentialWithoutLeakingTheProviderBody() {
    authServer.nextResponse(
        400, "{\"error\":\"invalid_grant\",\"error_description\":\"" + SENTINEL + "\"}");

    StepVerifier.create(fcm().probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.AUTH_REJECTED))
        .verifyComplete();

    assertFalse(loggedText().contains(SENTINEL));
  }

  @Test
  void fcmProbeMapsATokenServerFailureToProviderError() {
    authServer.nextResponse(500, "{}");

    StepVerifier.create(fcm().probe())
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.PROVIDER_ERROR))
        .verifyComplete();
  }

  @Test
  void fcmProbeWithoutCredentialsReportsMissingCredentials() {
    final FcmProviderProperties properties =
        new FcmProviderProperties(null, null, server.baseUrl(), authServer.baseUrl(), null, null);
    final FcmNotificationProvider provider =
        new FcmNotificationProvider(
            WebClient.builder().build(), properties, FcmCredentials.load(properties));

    StepVerifier.create(provider.probe())
        .assertNext(
            outcome -> assertEquals(ProbeErrorCode.CREDENTIALS_MISSING, outcome.errorCode()))
        .verifyComplete();
  }

  @Test
  void theFcmProbeIgnoresACachedTokenAndDoesNotReplaceIt() {
    final FcmProviderProperties properties =
        new FcmProviderProperties(
            FcmTestCredentials.shared().json(),
            null,
            server.baseUrl(),
            authServer.baseUrl() + "/token",
            10_000L,
            5_000L);
    final FcmAccessTokenProvider tokens =
        new FcmAccessTokenProvider(
            WebClient.builder().build(),
            FcmCredentials.load(properties).serviceAccount().orElseThrow(),
            properties.tokenUrl());
    authServer.nextResponse(200, "{\"access_token\":\"cached\",\"expires_in\":3600}");
    assertEquals("cached", tokens.accessToken().block());

    authServer.nextResponse(401, "{\"error\":\"" + SENTINEL + "\"}");

    StepVerifier.create(tokens.probe())
        .expectError(FcmAccessTokenProvider.AuthorizationRejectedException.class)
        .verify();
    assertEquals("cached", tokens.accessToken().block());
    assertEquals(2, authServer.requests().size());
  }

  @Test
  void anEmptyStatusIsReportedAsProviderError() {
    StepVerifier.create(ProviderProbes.classify(Mono.empty()))
        .assertNext(outcome -> assertFailed(outcome, ProbeErrorCode.PROVIDER_ERROR))
        .verifyComplete();
  }

  @Test
  void theSimulatedProbeAnswersLocallyAccordingToTheConfiguredResult() {
    assertTrue(simulated(AttemptResult.ACCEPTED, true).probe().block().isOk());
    assertEquals(
        ProbeErrorCode.SIMULATED_FAILURE,
        simulated(AttemptResult.RECOVERABLE_FAILURE, true).probe().block().errorCode());
    assertEquals(
        ProbeErrorCode.SIMULATED_FAILURE,
        simulated(AttemptResult.PERMANENT_FAILURE, true).probe().block().errorCode());
    assertEquals(
        ProbeErrorCode.CREDENTIALS_MISSING,
        simulated(AttemptResult.ACCEPTED, false).probe().block().errorCode());
    assertNotNull(simulated(AttemptResult.ACCEPTED, true).providerId());
  }

  private static SimulatedNotificationProvider simulated(
      final AttemptResult result, final boolean enabled) {
    return new SimulatedNotificationProvider(new SimulatedProviderProperties(result, enabled));
  }

  private static WebClient clientWithResponseTimeout(final String baseUrl, final long millis) {
    final HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
            .responseTimeout(Duration.ofMillis(millis));
    return WebClient.builder()
        .baseUrl(baseUrl)
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }
}
