package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.FcmCredentials;
import co.edu.uco.notification.infrastructure.config.FcmProviderProperties;
import co.edu.uco.notification.infrastructure.config.FcmServiceAccount;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class FcmNotificationProvider implements NotificationSenderPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(FcmNotificationProvider.class);
  private static final ProviderId PROVIDER_ID = ProviderId.of("fcm");
  private static final String SEND_PATH = "/v1/projects/{projectId}/messages:send";
  private static final int UNAUTHORIZED = 401;

  private final Supplier<WebClient> clientSource;
  private final Optional<String> disabledReason;
  private final String projectId;
  private final FcmAccessTokenProvider tokenProvider;

  @Autowired
  public FcmNotificationProvider(
      final ProviderHttpClients providerHttpClients,
      final ConfigurationView configurationView,
      final FcmProviderProperties properties,
      final FcmCredentials credentials) {
    this(
        ProviderHttpClients.providerClientSource(
            PROVIDER_ID.value(), properties.baseUrl(), providerHttpClients, configurationView),
        properties,
        credentials);
  }

  FcmNotificationProvider(
      final WebClient fixedClient,
      final FcmProviderProperties properties,
      final FcmCredentials credentials) {
    this(ProviderHttpClients.fixedClientSource(fixedClient), properties, credentials);
  }

  private FcmNotificationProvider(
      final Supplier<WebClient> clientSource,
      final FcmProviderProperties properties,
      final FcmCredentials credentials) {
    this.clientSource = Preconditions.requireNonNull(clientSource, "clientSource must not be null");
    Preconditions.requireNonNull(properties, "properties must not be null");
    Preconditions.requireNonNull(credentials, "credentials must not be null");
    this.disabledReason = credentials.disabledReason();
    final Optional<FcmServiceAccount> serviceAccount = credentials.serviceAccount();
    this.projectId = serviceAccount.map(FcmServiceAccount::projectId).orElse(null);
    this.tokenProvider =
        serviceAccount
            .map(
                account -> new FcmAccessTokenProvider(clientSource, account, properties.tokenUrl()))
            .orElse(null);
    disabledReason.ifPresent(reason -> ProviderLogs.disabled(LOGGER, PROVIDER_ID, reason));
  }

  @Override
  public Mono<AttemptResult> send(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    if (disabledReason.isPresent()) {
      return Mono.error(new ProviderDisabledException(PROVIDER_ID, disabledReason.get()));
    }
    final WebClient client = clientSource.get();
    return tokenProvider
        .accessToken()
        .flatMap(token -> dispatch(client, notification, token))
        .onErrorResume(error -> Mono.just(authorizationFailure(notification, error)));
  }

  private Mono<AttemptResult> dispatch(
      final WebClient client, final Notification notification, final String token) {
    final FcmSendRequest request =
        FcmSendRequest.of(
            notification.recipient().address(),
            notification.content().subject(),
            notification.content().body());
    return client
        .post()
        .uri(SEND_PATH, projectId)
        .headers(headers -> headers.setBearerAuth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchangeToMono(FcmNotificationProvider::toOutcome)
        .map(
            outcome -> {
              if (outcome.status() == UNAUTHORIZED) {
                tokenProvider.invalidate(token);
              }
              return classifyAndLog(notification, outcome);
            })
        .onErrorResume(
            error -> {
              final AttemptResult result = FcmResponseClassifier.classifyError(error);
              ProviderLogs.dispatchFailed(LOGGER, notification, PROVIDER_ID, result, error);
              return Mono.just(result);
            });
  }

  private static Mono<Outcome> toOutcome(final ClientResponse response) {
    final int status = response.statusCode().value();
    if (response.statusCode().is2xxSuccessful()) {
      return response
          .bodyToMono(FcmSendResponse.class)
          .onErrorReturn(FcmSendResponse.EMPTY)
          .defaultIfEmpty(FcmSendResponse.EMPTY)
          .map(body -> new Outcome(status, body.messageId(), null));
    }
    return response
        .bodyToMono(FcmErrorResponse.class)
        .onErrorReturn(FcmErrorResponse.EMPTY)
        .defaultIfEmpty(FcmErrorResponse.EMPTY)
        .map(body -> new Outcome(status, null, body.errorCode()));
  }

  private static AttemptResult classifyAndLog(
      final Notification notification, final Outcome outcome) {
    final AttemptResult result = FcmResponseClassifier.classifyStatus(outcome.status());
    if (result == AttemptResult.ACCEPTED) {
      ProviderLogs.dispatched(
          LOGGER, notification, PROVIDER_ID, result, outcome.status(), outcome.messageId());
    } else {
      ProviderLogs.rejectedByProvider(
          LOGGER,
          notification,
          PROVIDER_ID,
          result,
          outcome.status(),
          String.valueOf(outcome.errorCode()));
    }
    return result;
  }

  private static AttemptResult authorizationFailure(
      final Notification notification, final Throwable error) {
    if (error instanceof FcmAccessTokenProvider.AuthorizationRejectedException rejected) {
      final AttemptResult result = FcmResponseClassifier.classifyStatus(rejected.status());
      ProviderLogs.authorizationFailed(
          LOGGER, notification, PROVIDER_ID, result, rejected.status(), null);
      return result;
    }
    final AttemptResult result = FcmResponseClassifier.classifyError(error);
    ProviderLogs.authorizationFailed(LOGGER, notification, PROVIDER_ID, result, null, error);
    return result;
  }

  @Override
  public ProviderId providerId() {
    return PROVIDER_ID;
  }

  @Override
  public Optional<String> disabledReason() {
    return disabledReason;
  }

  @Override
  public boolean supportsAttachments() {
    return false;
  }

  private record Outcome(int status, String messageId, String errorCode) {}
}
