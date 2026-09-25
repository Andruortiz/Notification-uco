package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.config.FcmCredentials;
import co.edu.uco.notification.infrastructure.config.FcmProviderProperties;
import co.edu.uco.notification.infrastructure.config.FcmServiceAccount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class FcmAccessTokenProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ACCESS_TOKEN = "test-access-" + "token-1";
  private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

  private static FakeProviderServer authServer;
  private static FcmServiceAccount account;

  @BeforeAll
  static void startServer() {
    authServer = FakeProviderServer.start();
    account =
        FcmCredentials.load(
                new FcmProviderProperties(
                    FcmTestCredentials.shared().json(), null, null, null, null, null))
            .serviceAccount()
            .orElseThrow();
  }

  @AfterAll
  static void stopServer() {
    authServer.stop();
  }

  @BeforeEach
  void setUp() {
    authServer.reset();
    tokenResponse(ACCESS_TOKEN, 3600);
  }

  private static String tokenUrl() {
    return authServer.baseUrl() + "/token";
  }

  private static void tokenResponse(final String token, final long expiresIn) {
    authServer.nextResponse(
        200,
        "{\"access_token\":\""
            + token
            + "\",\"expires_in\":"
            + expiresIn
            + ",\"token_type\":\"Bearer\"}");
  }

  private static FcmAccessTokenProvider provider() {
    return new FcmAccessTokenProvider(WebClient.builder().build(), account, tokenUrl());
  }

  private static Map<String, String> form(final String body) {
    return Arrays.stream(body.split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
  }

  private static JsonNode decodeSegment(final String segment) throws Exception {
    return MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
  }

  @Test
  void exchangesASignedAssertionWithTheExpectedClaims() throws Exception {
    final long before = Instant.now().getEpochSecond();

    StepVerifier.create(provider().accessToken()).expectNext(ACCESS_TOKEN).verifyComplete();

    assertEquals(1, authServer.requests().size());
    final FakeProviderServer.RecordedRequest request = authServer.requests().get(0);
    assertEquals("/token", request.path());
    assertTrue(request.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
    final Map<String, String> form = form(request.body());
    assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form.get("grant_type"));

    final String[] parts = form.get("assertion").split("\\.");
    assertEquals(3, parts.length);
    final JsonNode header = decodeSegment(parts[0]);
    assertEquals("RS256", header.get("alg").asText());
    assertEquals("JWT", header.get("typ").asText());
    final JsonNode claims = decodeSegment(parts[1]);
    assertEquals(FcmTestCredentials.CLIENT_EMAIL, claims.get("iss").asText());
    assertEquals(SCOPE, claims.get("scope").asText());
    assertEquals(tokenUrl(), claims.get("aud").asText());
    final long iat = claims.get("iat").asLong();
    assertTrue(iat >= before && iat <= Instant.now().getEpochSecond());
    assertEquals(iat + 3600, claims.get("exp").asLong());

    final Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(FcmTestCredentials.shared().publicKey());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
  }

  @Test
  void reusesTheTokenWhileItIsValid() {
    final FcmAccessTokenProvider provider = provider();

    for (int i = 0; i < 10; i++) {
      StepVerifier.create(provider.accessToken()).expectNext(ACCESS_TOKEN).verifyComplete();
    }

    assertEquals(1, authServer.requests().size());
  }

  @Test
  void concurrentRequestsShareASingleExchange() {
    final FcmAccessTokenProvider provider = provider();
    authServer.nextDelay(300);

    StepVerifier.create(Flux.range(0, 10).flatMap(i -> provider.accessToken()))
        .expectNextCount(10)
        .verifyComplete();

    assertEquals(1, authServer.requests().size());
  }

  @Test
  void renewsATokenThatExpiresWithinTheRenewalMargin() {
    tokenResponse(ACCESS_TOKEN, 120);
    final FcmAccessTokenProvider provider = provider();

    for (int i = 0; i < 3; i++) {
      StepVerifier.create(provider.accessToken()).expectNext(ACCESS_TOKEN).verifyComplete();
    }

    assertEquals(3, authServer.requests().size());
  }

  @Test
  void invalidatingTheCurrentTokenForcesANewExchange() {
    final FcmAccessTokenProvider provider = provider();
    StepVerifier.create(provider.accessToken()).expectNext(ACCESS_TOKEN).verifyComplete();

    provider.invalidate(ACCESS_TOKEN);
    final String renewed = "test-access-" + "token-2";
    tokenResponse(renewed, 3600);

    StepVerifier.create(provider.accessToken()).expectNext(renewed).verifyComplete();
    StepVerifier.create(provider.accessToken()).expectNext(renewed).verifyComplete();
    assertEquals(2, authServer.requests().size());
  }

  @Test
  void invalidatingAnOlderTokenKeepsTheCurrentOne() {
    final FcmAccessTokenProvider provider = provider();
    StepVerifier.create(provider.accessToken()).expectNext(ACCESS_TOKEN).verifyComplete();

    provider.invalidate("some-other-" + "token");

    StepVerifier.create(provider.accessToken()).expectNext(ACCESS_TOKEN).verifyComplete();
    assertEquals(1, authServer.requests().size());
  }

  @Test
  void aRejectedExchangeCarriesItsStatusAndIsNotMemorized() {
    final FcmAccessTokenProvider provider = provider();
    authServer.nextResponse(
        400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}");

    StepVerifier.create(provider.accessToken())
        .expectErrorSatisfies(
            error -> {
              assertTrue(error instanceof FcmAccessTokenProvider.AuthorizationRejectedException);
              assertEquals(
                  400, ((FcmAccessTokenProvider.AuthorizationRejectedException) error).status());
              assertFalse(error.getMessage().contains("Invalid JWT"));
            })
        .verify();

    tokenResponse(ACCESS_TOKEN, 3600);
    StepVerifier.create(provider.accessToken()).expectNext(ACCESS_TOKEN).verifyComplete();
    assertEquals(2, authServer.requests().size());
  }

  @Test
  void aSuccessfulResponseWithoutATokenIsAnError() {
    authServer.nextResponse(200, "{\"token_type\":\"Bearer\"}");

    StepVerifier.create(provider().accessToken())
        .expectErrorSatisfies(
            error ->
                assertFalse(error instanceof FcmAccessTokenProvider.AuthorizationRejectedException))
        .verify();
  }

  @Test
  void anUnreadableSuccessfulResponseIsAnError() {
    authServer.nextResponse(200, "not json");

    StepVerifier.create(provider().accessToken()).expectError().verify();
  }

  @Test
  void theExchangeTimesOutWithinTheConfiguredDuration() {
    final WebClient shortTimeoutClient =
        WebClient.builder()
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(Duration.ofMillis(500))))
            .build();
    final FcmAccessTokenProvider provider =
        new FcmAccessTokenProvider(shortTimeoutClient, account, tokenUrl());
    authServer.nextDelay(3_000);

    final Instant start = Instant.now();
    StepVerifier.create(provider.accessToken())
        .expectErrorSatisfies(
            error ->
                assertFalse(error instanceof FcmAccessTokenProvider.AuthorizationRejectedException))
        .verify();
    final Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "elapsed was " + elapsed);
  }
}
