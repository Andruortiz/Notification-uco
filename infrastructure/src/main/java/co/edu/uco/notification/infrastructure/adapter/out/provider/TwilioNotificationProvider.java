package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.TwilioProviderProperties;
import co.edu.uco.notification.utils.PhoneNumbers;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class TwilioNotificationProvider implements NotificationSenderPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(TwilioNotificationProvider.class);
  private static final ProviderId PROVIDER_ID = ProviderId.of("twilio");
  private static final String SEND_PATH = "/2010-04-01/Accounts/{accountSid}/Messages.json";

  private final WebClient webClient;
  private final TwilioProviderProperties properties;
  private final Optional<String> disabledReason;

  public TwilioNotificationProvider(
      @Qualifier("twilioWebClient") final WebClient twilioWebClient,
      final TwilioProviderProperties properties) {
    this.webClient =
        Preconditions.requireNonNull(twilioWebClient, "twilioWebClient must not be null");
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
    this.disabledReason = properties.disabledReason();
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
    final String recipient = notification.recipient().address();
    if (!PhoneNumbers.isE164(recipient)) {
      LOGGER.info(
          "Notification rejected before calling provider notificationId={} tenantId={} "
              + "providerId={} reason=invalid-recipient-format",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value());
      return Mono.just(AttemptResult.PERMANENT_FAILURE);
    }
    return webClient
        .post()
        .uri(SEND_PATH, properties.accountSid())
        .headers(headers -> headers.setBasicAuth(properties.accountSid(), properties.authToken()))
        .accept(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromFormData(toForm(notification, recipient)))
        .exchangeToMono(response -> toOutcome(response))
        .map(outcome -> classifyAndLog(notification, outcome))
        .onErrorResume(
            error -> {
              final AttemptResult result = TwilioResponseClassifier.classifyError(error);
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

  private MultiValueMap<String, String> toForm(
      final Notification notification, final String recipient) {
    final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("To", recipient);
    form.add("From", properties.fromNumber());
    form.add("Body", notification.content().body());
    return form;
  }

  private static Mono<Outcome> toOutcome(final ClientResponse response) {
    final int status = response.statusCode().value();
    return response
        .bodyToMono(TwilioApiResponse.class)
        .onErrorReturn(TwilioApiResponse.EMPTY)
        .defaultIfEmpty(TwilioApiResponse.EMPTY)
        .map(body -> new Outcome(status, body));
  }

  private static AttemptResult classifyAndLog(
      final Notification notification, final Outcome outcome) {
    final AttemptResult result = TwilioResponseClassifier.classifyStatus(outcome.status());
    final String maskedRecipient = PhoneNumbers.mask(notification.recipient().address());
    if (result == AttemptResult.ACCEPTED) {
      LOGGER.info(
          "Notification dispatched notificationId={} tenantId={} providerId={} result={} "
              + "httpStatus={} providerMessageId={} recipient={}",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value(),
          result,
          outcome.status(),
          outcome.body().sid(),
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
          outcome.body().code(),
          maskedRecipient);
    }
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

  private record Outcome(int status, TwilioApiResponse body) {}
}
