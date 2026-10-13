package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.BrevoProviderProperties;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class BrevoNotificationProvider implements NotificationSenderPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(BrevoNotificationProvider.class);
  private static final ProviderId PROVIDER_ID = ProviderId.of("brevo");
  private static final String SEND_PATH = "/v3/smtp/email";

  static final long MAX_ATTACHMENTS_BYTES = 4_000_000L;

  private final Supplier<WebClient> clientSource;
  private final BrevoProviderProperties properties;
  private final AttachmentContentLoader attachmentContentLoader;
  private final Optional<String> disabledReason;

  @Autowired
  public BrevoNotificationProvider(
      final ProviderHttpClients providerHttpClients,
      final ConfigurationView configurationView,
      final BrevoProviderProperties properties,
      final AttachmentContentLoader attachmentContentLoader) {
    this(
        ProviderHttpClients.providerClientSource(
            PROVIDER_ID.value(), properties.baseUrl(), providerHttpClients, configurationView),
        properties,
        attachmentContentLoader);
  }

  BrevoNotificationProvider(
      final WebClient fixedClient,
      final BrevoProviderProperties properties,
      final AttachmentContentLoader attachmentContentLoader) {
    this(ProviderHttpClients.fixedClientSource(fixedClient), properties, attachmentContentLoader);
  }

  private BrevoNotificationProvider(
      final Supplier<WebClient> clientSource,
      final BrevoProviderProperties properties,
      final AttachmentContentLoader attachmentContentLoader) {
    this.clientSource = clientSource;
    this.properties = Preconditions.requireNonNull(properties, "properties must not be null");
    this.attachmentContentLoader =
        Preconditions.requireNonNull(
            attachmentContentLoader, "attachmentContentLoader must not be null");
    this.disabledReason = properties.disabledReason();
    disabledReason.ifPresent(reason -> ProviderLogs.disabled(LOGGER, PROVIDER_ID, reason));
  }

  @Override
  public Mono<AttemptResult> send(final Notification notification) {
    Preconditions.requireNonNull(notification, "notification must not be null");
    if (disabledReason.isPresent()) {
      return Mono.error(new ProviderDisabledException(PROVIDER_ID, disabledReason.get()));
    }
    final String subject = notification.content().subject();
    if (subject == null || subject.isBlank()) {
      ProviderLogs.rejectedBeforeCall(LOGGER, notification, PROVIDER_ID, "missing-subject");
      return Mono.just(AttemptResult.PERMANENT_FAILURE);
    }
    final List<Attachment> attachments = notification.content().attachments();
    if (attachments.stream().mapToLong(Attachment::sizeBytes).sum() > MAX_ATTACHMENTS_BYTES) {
      ProviderLogs.rejectedBeforeCall(LOGGER, notification, PROVIDER_ID, "attachments-too-large");
      return Mono.just(AttemptResult.PERMANENT_FAILURE);
    }
    final WebClient client = clientSource.get();
    return attachmentContentLoader
        .load(attachments)
        .map(files -> toRequest(notification, subject, files))
        .flatMap(request -> post(client, notification, request))
        .onErrorResume(
            AttachmentContentLoader.AttachmentContentException.class,
            error -> {
              ProviderLogs.rejectedBeforeCall(
                  LOGGER, notification, PROVIDER_ID, "attachment-content-unavailable");
              return Mono.just(AttemptResult.PERMANENT_FAILURE);
            })
        .onErrorResume(
            error -> {
              final AttemptResult result = BrevoResponseClassifier.classifyError(error);
              logFailure(notification, result, error);
              return Mono.just(result);
            });
  }

  private Mono<AttemptResult> post(
      final WebClient client, final Notification notification, final BrevoEmailRequest request) {
    return client
        .post()
        .uri(SEND_PATH)
        .header("api-key", properties.apiKey())
        .bodyValue(request)
        .exchangeToMono(
            response -> {
              final int status = response.statusCode().value();
              return response
                  .bodyToMono(BrevoApiResponse.class)
                  .onErrorReturn(BrevoApiResponse.EMPTY)
                  .defaultIfEmpty(BrevoApiResponse.EMPTY)
                  .map(body -> classifyAndLog(notification, status, body));
            })
        .onErrorResume(
            error -> {
              final AttemptResult result = BrevoResponseClassifier.classifyError(error);
              logFailure(notification, result, error);
              return Mono.just(result);
            });
  }

  private static AttemptResult classifyAndLog(
      final Notification notification, final int status, final BrevoApiResponse body) {
    final AttemptResult result = BrevoResponseClassifier.classifyStatus(status);
    if (result == AttemptResult.ACCEPTED) {
      ProviderLogs.dispatched(LOGGER, notification, PROVIDER_ID, result, status, body.messageId());
    } else {
      ProviderLogs.dispatchedWithoutDetails(LOGGER, notification, PROVIDER_ID, result);
    }
    return result;
  }

  private void logFailure(
      final Notification notification, final AttemptResult result, final Throwable error) {
    ProviderLogs.dispatchFailed(LOGGER, notification, PROVIDER_ID, result, error);
  }

  private BrevoEmailRequest toRequest(
      final Notification notification,
      final String subject,
      final List<AttachmentContentLoader.LoadedAttachment> files) {
    final BrevoEmailRequest.Sender sender =
        new BrevoEmailRequest.Sender(
            properties.senderEmail(), blankToNull(properties.senderName()));
    final List<BrevoEmailRequest.Contact> to =
        List.of(new BrevoEmailRequest.Contact(notification.recipient().address()));
    final Map<String, String> headers =
        Map.of("Idempotency-Key", notification.notificationId().value());
    final List<BrevoEmailRequest.Attachment> attachment =
        files.stream()
            .map(file -> new BrevoEmailRequest.Attachment(file.fileName(), file.contentBase64()))
            .toList();
    return new BrevoEmailRequest(
        sender,
        to,
        subject,
        notification.content().body(),
        headers,
        attachment,
        correlationTags(notification));
  }

  private static List<String> correlationTags(final Notification notification) {
    final CorrelationId correlationId = notification.correlationId();
    return correlationId == null ? null : List.of(correlationId.value());
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

  @Override
  public boolean supportsAttachments() {
    return true;
  }
}
