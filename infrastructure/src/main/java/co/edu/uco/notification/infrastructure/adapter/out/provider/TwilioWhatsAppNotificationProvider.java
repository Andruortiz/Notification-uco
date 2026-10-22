package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.TwilioWhatsAppProviderProperties;
import co.edu.uco.notification.utils.PhoneNumbers;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TwilioWhatsAppNotificationProvider implements NotificationSenderPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TwilioWhatsAppNotificationProvider.class);
  private static final ProviderId PROVIDER_ID = ProviderId.of("twilio-whatsapp");
  private static final String SEND_PATH = "/2010-04-01/Accounts/{accountSid}/Messages.json";
  private static final String CHANNEL_PREFIX = "whatsapp:";
  private static final Pattern CONTENT_SID = Pattern.compile("^HX[0-9a-fA-F]{32}$");
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final Supplier<WebClient> clientSource;
  private final TwilioWhatsAppProviderProperties properties;
  private final Optional<String> disabledReason;

  @Autowired
  public TwilioWhatsAppNotificationProvider(
      final ProviderHttpClients providerHttpClients,
      final ConfigurationView configurationView,
      final TwilioWhatsAppProviderProperties properties) {
    this(
        ProviderHttpClients.providerClientSource(
            PROVIDER_ID.value(), properties.baseUrl(), providerHttpClients, configurationView),
        properties);
  }

  TwilioWhatsAppNotificationProvider(
      final WebClient fixedClient, final TwilioWhatsAppProviderProperties properties) {
    this(ProviderHttpClients.fixedClientSource(fixedClient), properties);
  }

  private TwilioWhatsAppNotificationProvider(
      final Supplier<WebClient> clientSource, final TwilioWhatsAppProviderProperties properties) {
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
      return rejectedBeforeCall(notification, "invalid-recipient-format");
    }
    final NotificationContent content = notification.content();
    if (content.hasProviderTemplate()
        && !CONTENT_SID.matcher(content.providerTemplate().templateId()).matches()) {
      return rejectedBeforeCall(notification, "invalid-provider-template-format");
    }
    return clientSource
        .get()
        .post()
        .uri(SEND_PATH, properties.accountSid())
        .headers(headers -> headers.setBasicAuth(properties.accountSid(), properties.authToken()))
        .accept(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromFormData(toForm(content, recipient)))
        .exchangeToMono(TwilioWhatsAppNotificationProvider::toOutcome)
        .map(outcome -> classifyAndLog(notification, outcome))
        .onErrorResume(
            error -> {
              final AttemptResult result = TwilioResponseClassifier.classifyError(error);
              ProviderLogs.dispatchFailed(LOGGER, notification, PROVIDER_ID, result, error);
              return Mono.just(result);
            });
  }

  private static Mono<AttemptResult> rejectedBeforeCall(
      final Notification notification, final String reason) {
    ProviderLogs.rejectedBeforeCall(LOGGER, notification, PROVIDER_ID, reason);
    return Mono.just(AttemptResult.PERMANENT_FAILURE);
  }

  private MultiValueMap<String, String> toForm(
      final NotificationContent content, final String recipient) {
    final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("To", CHANNEL_PREFIX + recipient);
    form.add("From", CHANNEL_PREFIX + properties.fromNumber());
    if (content.hasProviderTemplate()) {
      form.add("ContentSid", content.providerTemplate().templateId());
      form.add(
          "ContentVariables", MAPPER.writeValueAsString(content.providerTemplate().variables()));
    } else {
      form.add("Body", content.body());
    }
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
          String.valueOf(outcome.body().code()),
          WhatsAppFailureReasons.reasonOf(outcome.body().code()),
          outcome.body().message(),
          outcome.body().moreInfo());
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
