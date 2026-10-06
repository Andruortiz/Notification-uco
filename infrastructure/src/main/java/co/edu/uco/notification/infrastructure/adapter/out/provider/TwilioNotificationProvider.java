package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.TwilioProviderProperties;
import co.edu.uco.notification.utils.PhoneNumbers;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

  private final Supplier<WebClient> clientSource;
  private final TwilioProviderProperties properties;
  private final Optional<String> disabledReason;

  @Autowired
  public TwilioNotificationProvider(
      final ProviderHttpClients providerHttpClients,
      final ConfigurationView configurationView,
      final TwilioProviderProperties properties) {
    this(
        ProviderHttpClients.providerClientSource(
            PROVIDER_ID.value(), properties.baseUrl(), providerHttpClients, configurationView),
        properties);
  }

  TwilioNotificationProvider(
      final WebClient fixedClient, final TwilioProviderProperties properties) {
    this(ProviderHttpClients.fixedClientSource(fixedClient), properties);
  }

  private TwilioNotificationProvider(
      final Supplier<WebClient> clientSource, final TwilioProviderProperties properties) {
    this.clientSource = clientSource;
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
    this.disabledReason = properties.disabledReason();
    disabledReason.ifPresent(reason -> ProviderLogs.disabled(LOGGER, PROVIDER_ID, reason));
  }

  @Override
  public Mono<AttemptResult> send(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    if (disabledReason.isPresent()) {
      return Mono.error(new ProviderDisabledException(PROVIDER_ID, disabledReason.get()));
    }
    final String recipient = notification.recipient().address();
    if (!PhoneNumbers.isE164(recipient)) {
      ProviderLogs.rejectedBeforeCall(
          LOGGER, notification, PROVIDER_ID, "invalid-recipient-format");
      return Mono.just(AttemptResult.PERMANENT_FAILURE);
    }
    return clientSource
        .get()
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
              ProviderLogs.dispatchFailed(LOGGER, notification, PROVIDER_ID, result, error);
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
    if (result == AttemptResult.ACCEPTED) {
      ProviderLogs.dispatched(
          LOGGER, notification, PROVIDER_ID, result, outcome.status(), outcome.body().sid());
    } else {
      ProviderLogs.rejectedByProvider(
          LOGGER,
          notification,
          PROVIDER_ID,
          result,
          outcome.status(),
          String.valueOf(outcome.body().code()));
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

  @Override
  public boolean supportsAttachments() {
    return false;
  }

  private record Outcome(int status, TwilioApiResponse body) {}
}
