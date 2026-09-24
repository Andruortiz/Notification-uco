package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.infrastructure.config.FcmServiceAccount;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public final class FcmAccessTokenProvider {

  private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
  private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  private static final long ASSERTION_LIFETIME_SECONDS = 3_600L;
  private static final long RENEWAL_MARGIN_SECONDS = 300L;
  private static final Map<String, String> HEADER = Map.of("alg", "RS256", "typ", "JWT");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private final WebClient webClient;
  private final FcmServiceAccount serviceAccount;
  private final URI tokenUri;
  private final String audience;
  private final AtomicReference<String> revokedToken = new AtomicReference<>();
  private final Mono<AccessToken> cachedToken;

  public FcmAccessTokenProvider(
      final WebClient webClient, final FcmServiceAccount serviceAccount, final String tokenUrl) {
    this.webClient = Preconditions.requireNonNull(webClient, "webClient must not be null");
    this.serviceAccount =
        Preconditions.requireNonNull(serviceAccount, "serviceAccount must not be null");
    Preconditions.requireNonBlank(tokenUrl, "tokenUrl must not be blank");
    this.tokenUri = URI.create(tokenUrl);
    this.audience = tokenUrl;
    this.cachedToken = Mono.defer(this::exchange).cacheInvalidateIf(this::mustRenew);
  }

  public Mono<String> accessToken() {
    return cachedToken.map(AccessToken::value);
  }

  public void invalidate(final String token) {
    revokedToken.set(token);
  }

  private boolean mustRenew(final AccessToken token) {
    return !Instant.now().isBefore(token.renewAt()) || token.value().equals(revokedToken.get());
  }

  private Mono<AccessToken> exchange() {
    final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", GRANT_TYPE);
    form.add("assertion", assertion(Instant.now()));
    return webClient
        .post()
        .uri(tokenUri)
        .accept(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromFormData(form))
        .exchangeToMono(FcmAccessTokenProvider::readToken)
        .switchIfEmpty(
            Mono.error(new IllegalStateException("FCM authorization response without a token")))
        .map(FcmAccessTokenProvider::toAccessToken);
  }

  private static Mono<TokenResponse> readToken(final ClientResponse response) {
    if (!response.statusCode().is2xxSuccessful()) {
      final int status = response.statusCode().value();
      return response.releaseBody().then(Mono.error(new AuthorizationRejectedException(status)));
    }
    return response
        .bodyToMono(TokenResponse.class)
        .filter(body -> body.accessToken() != null && !body.accessToken().isBlank());
  }

  private static AccessToken toAccessToken(final TokenResponse response) {
    final long expiresIn = response.expiresIn() == null ? 0L : response.expiresIn();
    final long validFor = Math.max(0L, expiresIn - RENEWAL_MARGIN_SECONDS);
    return new AccessToken(response.accessToken(), Instant.now().plusSeconds(validFor));
  }

  private String assertion(final Instant now) {
    final long issuedAt = now.getEpochSecond();
    final Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", serviceAccount.clientEmail());
    claims.put("scope", SCOPE);
    claims.put("aud", audience);
    claims.put("iat", issuedAt);
    claims.put("exp", issuedAt + ASSERTION_LIFETIME_SECONDS);
    final String signingInput = encode(HEADER) + "." + encode(claims);
    final byte[] signature = serviceAccount.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + BASE64URL.encodeToString(signature);
  }

  private static String encode(final Object value) {
    try {
      return BASE64URL.encodeToString(MAPPER.writeValueAsBytes(value));
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException("could not encode the FCM authorization assertion");
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") Long expiresIn) {

    @Override
    public String toString() {
      return "TokenResponse[expiresIn=" + expiresIn + "]";
    }
  }

  private record AccessToken(String value, Instant renewAt) {

    @Override
    public String toString() {
      return "AccessToken[renewAt=" + renewAt + "]";
    }
  }

  public static final class AuthorizationRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public AuthorizationRejectedException(final int status) {
      super("FCM authorization rejected httpStatus=" + status);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
