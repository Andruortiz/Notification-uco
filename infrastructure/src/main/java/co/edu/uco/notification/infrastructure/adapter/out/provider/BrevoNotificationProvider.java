package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.BrevoProviderProperties;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class BrevoNotificationProvider implements NotificationSenderPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(BrevoNotificationProvider.class);
  private static final ProviderId PROVIDER_ID = ProviderId.of("brevo");
  private static final String SEND_PATH = "/v3/smtp/email";

  private final WebClient webClient;
  private final BrevoProviderProperties properties;
  private final Optional<String> disabledReason;

  public BrevoNotificationProvider(
      @Qualifier("brevoWebClient") final WebClient brevoWebClient,
      final BrevoProviderProperties properties) {
    this.webClient =
        Preconditions.requireNonNull(brevoWebClient, "brevoWebClient must not be null");
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
    final String subject = notification.content().subject();
    if (subject == null || subject.isBlank()) {
      LOGGER.info(
          "Notification rejected before calling provider notificationId={} tenantId={} "
              + "providerId={} reason=missing-subject",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value());
      return Mono.just(AttemptResult.PERMANENT_FAILURE);
    }
    final BrevoEmailRequest request = toRequest(notification, subject);
    return webClient
        .post()
        .uri(SEND_PATH)
        .header("api-key", properties.apiKey())
        .bodyValue(request)
        .exchangeToMono(
            response ->
                Mono.just(BrevoResponseClassifier.classifyStatus(response.statusCode().value())))
        .doOnNext(result -> logOutcome(notification, result, null))
        .onErrorResume(
            error -> {
              final AttemptResult result = BrevoResponseClassifier.classifyError(error);
              logOutcome(notification, result, error);
              return Mono.just(result);
            });
  }

  private void logOutcome(
      final Notification notification, final AttemptResult result, final Throwable error) {
    if (error == null) {
      LOGGER.info(
          "Notification dispatched notificationId={} tenantId={} providerId={} result={}",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value(),
          result);
    } else {
      LOGGER.warn(
          "Notification dispatch failed notificationId={} tenantId={} providerId={} result={} "
              + "errorType={}",
          notification.notificationId().value(),
          notification.tenantId().value(),
          PROVIDER_ID.value(),
          result,
          error.getClass().getSimpleName());
    }
  }

  private BrevoEmailRequest toRequest(final Notification notification, final String subject) {
    final BrevoEmailRequest.Sender sender =
        new BrevoEmailRequest.Sender(
            properties.senderEmail(), blankToNull(properties.senderName()));
    final List<BrevoEmailRequest.Contact> to =
        List.of(new BrevoEmailRequest.Contact(notification.recipient().address()));
    final Map<String, String> headers =
        Map.of("Idempotency-Key", notification.notificationId().value());
    return new BrevoEmailRequest(sender, to, subject, notification.content().body(), headers);
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value;
  }

  @Override
  public ProviderId providerId() {
    return PROVIDER_ID;
  }

  @Override
  public Optional<String> disabledReason() {
    return disabledReason;
  }
}
