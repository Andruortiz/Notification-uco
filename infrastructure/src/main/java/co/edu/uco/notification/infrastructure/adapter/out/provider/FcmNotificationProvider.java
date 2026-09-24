package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.FcmCredentials;
import co.edu.uco.notification.infrastructure.config.FcmProviderProperties;
import co.edu.uco.notification.infrastructure.config.FcmServiceAccount;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
  private static final String MASK = "***";
  private static final int VISIBLE_CHARACTERS = 4;

  private final WebClient webClient;
  private final Optional<String> disabledReason;
  private final String projectId;
  private final FcmAccessTokenProvider tokenProvider;

  public FcmNotificationProvider(
      @Qualifier("fcmWebClient") final WebClient fcmWebClient,
      final FcmProviderProperties properties,
      final FcmCredentials credentials) {
    this.webClient = Preconditions.requireNonNull(fcmWebClient, "fcmWebClient must not be null");
    Preconditions.requireNonNull(properties, "properties must not be null");
    Preconditions.requireNonNull(credentials, "credentials must not be null");
    this.disabledReason = credentials.disabledReason();
    final Optional<FcmServiceAccount> serviceAccount = credentials.serviceAccount();
    this.projectId = serviceAccount.map(FcmServiceAccount::projectId).orElse(null);
    this.tokenProvider =
        serviceAccount
            .map(account -> new FcmAccessTokenProvider(webClient, account, properties.tokenUrl()))
            .orElse(null);
    disabledReason.ifPresent(
        reason ->
            LOGGER.warn(
                "Notification sender disabled providerId={} reason={}",
                PROVIDER_ID.value(),
                reason));
  }

  @Override
  public Mono<AttemptResult> send(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    if (disabledReason.isPresent()) {
      return Mono.error(new ProviderDisabledException(PROVIDER_ID, disabledReason.get()));
    }
    return tokenProvider
        .accessToken()
        .flatMap(token -> dispatch(notification, token))
        .onErrorResume(error -> Mono.just(authorizationFailure(notification, error)));
  }

  private Mono<AttemptResult> dispatch(final Notification notification, final String token) {
    final FcmSendRequest request =
        FcmSendRequest.of(
            notification.recipient().address(),
            notification.content().subject(),
            notification.content().body());
    return webClient
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
              LOGGER.warn(
                  "Notification dispatch failed notificationId={} tenantId={} providerId={} "
                      + "result={} errorType={}",
                  notification.notificationId().value(),
                  notification.tenantId().value(),
                  PROVIDER_ID.value(),
                  result,
                  error.getClass().getSimpleName());
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
    final String maskedRecipient = mask(notification.recipient().address());
    if (result == AttemptResult.ACCEPTED) {
      LOGGER.info(
          "Notification dispatched notificationId={} tenantId={} providerId={} result={} "
              + "httpStatus={} providerMessageId={} recipient={}",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value(),
          result,
          outcome.status(),
          outcome.messageId(),
          maskedRecipient);
    } else {
      LOGGER.warn(
          "Notification rejected by provider notificationId={} tenantId={} providerId={} "
              + "result={} httpStatus={} providerErrorCode={} recipient={}",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value(),
          result,
          outcome.status(),
          outcome.errorCode(),
          maskedRecipient);
    }
    return result;
  }

  private static AttemptResult authorizationFailure(
      final Notification notification, final Throwable error) {
    if (error instanceof FcmAccessTokenProvider.AuthorizationRejectedException rejected) {
      final AttemptResult result = FcmResponseClassifier.classifyStatus(rejected.status());
      LOGGER.warn(
          "Notification authorization failed notificationId={} tenantId={} providerId={} "
              + "stage=authorization result={} httpStatus={}",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value(),
          result,
          rejected.status());
      return result;
    }
    final AttemptResult result = FcmResponseClassifier.classifyError(error);
    LOGGER.warn(
        "Notification authorization failed notificationId={} tenantId={} providerId={} "
            + "stage=authorization result={} errorType={}",
        notification.notificationId().value(),
        notification.tenantId().value(),
        PROVIDER_ID.value(),
        result,
        error.getClass().getSimpleName());
    return result;
  }

  private static String mask(final String value) {
    if (value == null || value.length() <= VISIBLE_CHARACTERS) {
      return MASK;
    }
    return MASK + value.substring(value.length() - VISIBLE_CHARACTERS);
  }

  @Override
  public ProviderId providerId() {
    return PROVIDER_ID;
  }

  private record Outcome(int status, String messageId, String errorCode) {}
}
