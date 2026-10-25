package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationMetadata;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.NotificationAlreadyAcceptedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationStateNotAllowedException;
import co.edu.uco.notification.core.exception.ResendChannelInvalidException;
import co.edu.uco.notification.core.port.in.ResendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResendNotificationServiceTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final String PHONE = "+573001234567";

  private final NotificationRepository repository = Mockito.mock(NotificationRepository.class);
  private final SendNotificationUseCase send = Mockito.mock(SendNotificationUseCase.class);
  private ResendNotificationService service;

  @BeforeEach
  void setUp() {
    service = new ResendNotificationService(repository, send);
  }

  private static Notification original(
      final NotificationStatus status, final String externalId, final NotificationContent content) {
    return Notification.reconstitute(
        NotificationId.newId(),
        new NotificationRouting(
            TENANT,
            ExternalId.of(externalId),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(content, Priority.HIGH),
        status,
        new NotificationMetadata(Instant.now(), 3L),
        List.of());
  }

  private static Notification failed() {
    return original(NotificationStatus.FAILED, "order-1", NotificationContent.of("S", "Body"));
  }

  private static ResendNotificationCommand command(
      final Notification original, final String channel) {
    return new ResendNotificationCommand(
        TENANT, original.notificationId(), ChannelType.of(channel), Recipient.of(PHONE), null);
  }

  private void stubFound(final Notification notification) {
    when(repository.findById(notification.notificationId())).thenReturn(Mono.just(notification));
  }

  @Test
  void aFailedNotificationIsSentAsANewOneWithTheDerivedExternalIdAndTheSameContent() {
    final Notification original = failed();
    final Notification created =
        original(NotificationStatus.PENDING, "order-1-resend-SMS", original.content());
    stubFound(original);
    when(repository.findById(created.notificationId())).thenReturn(Mono.just(created));
    when(send.send(any()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    created.notificationId(), NotificationStatus.PENDING, false)));

    StepVerifier.create(service.resend(command(original, "SMS")))
        .assertNext(view -> assertEquals(created.notificationId(), view.notificationId()))
        .verifyComplete();

    final ArgumentCaptor<SendNotificationCommand> sent =
        ArgumentCaptor.forClass(SendNotificationCommand.class);
    verify(send).send(sent.capture());
    assertEquals("order-1-resend-SMS", sent.getValue().externalId().value());
    assertEquals(ChannelType.of("SMS"), sent.getValue().channelType());
    assertEquals(Recipient.of(PHONE), sent.getValue().recipient());
    assertEquals(original.recipientId(), sent.getValue().recipientId());
    assertEquals(original.content(), sent.getValue().content());
    assertEquals(Priority.HIGH, sent.getValue().priority());
    assertEquals(TENANT, sent.getValue().tenantId());
    assertEquals(NotificationStatus.FAILED, original.status());
    verify(repository, never()).save(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = NotificationStatus.class,
      names = {"PENDING", "IN_PROCESS", "RECOVERABLE", "DELIVERED", "DISCARDED"})
  void onlyAFailedNotificationCanBeResent(final NotificationStatus status) {
    final Notification original = original(status, "order-1", NotificationContent.of("S", "Body"));
    stubFound(original);

    StepVerifier.create(service.resend(command(original, "SMS")))
        .expectError(NotificationStateNotAllowedException.class)
        .verify();

    verify(send, never()).send(any());
  }

  @Test
  void theSameChannelIsRejected() {
    final Notification original = failed();
    stubFound(original);

    StepVerifier.create(service.resend(command(original, "EMAIL")))
        .expectError(ResendChannelInvalidException.class)
        .verify();

    verify(send, never()).send(any());
  }

  @Test
  void aNotificationWithAttachmentsIsRejected() {
    final byte[] bytes = new byte[] {1, 2, 3};
    final Notification original =
        original(
            NotificationStatus.FAILED,
            "order-1",
            NotificationContent.of(
                "S",
                "Body",
                List.of(
                    new Attachment(
                        TENANT,
                        "invoice.pdf",
                        "application/pdf",
                        bytes.length,
                        Sha256Digest.of(bytes),
                        new AttachmentSource.EmbeddedContent(bytes)))));
    stubFound(original);

    StepVerifier.create(service.resend(command(original, "SMS")))
        .expectError(ResendChannelInvalidException.class)
        .verify();

    verify(send, never()).send(any());
  }

  @Test
  void aDerivedExternalIdLongerThanTheLimitIsRejected() {
    final Notification original =
        original(
            NotificationStatus.FAILED,
            "x".repeat(ResendNotificationService.MAX_EXTERNAL_ID_LENGTH),
            NotificationContent.of("S", "Body"));
    stubFound(original);

    StepVerifier.create(service.resend(command(original, "SMS")))
        .expectError(ResendChannelInvalidException.class)
        .verify();

    verify(send, never()).send(any());
  }

  @Test
  void aRepeatedResendIsReportedAsAlreadyAccepted() {
    final Notification original = failed();
    stubFound(original);
    when(send.send(any()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, true)));

    StepVerifier.create(service.resend(command(original, "SMS")))
        .expectError(NotificationAlreadyAcceptedException.class)
        .verify();
  }

  @Test
  void anUnavailableChannelErrorIsPropagated() {
    final Notification original = failed();
    stubFound(original);
    when(send.send(any()))
        .thenReturn(Mono.error(new ChannelNotAvailableException(ChannelType.of("SMS"))));

    StepVerifier.create(service.resend(command(original, "SMS")))
        .expectError(ChannelNotAvailableException.class)
        .verify();
  }

  @Test
  void aForeignAndAnUnknownNotificationGiveNotFoundWithoutSending() {
    final Notification foreign = failed();
    stubFound(foreign);
    final NotificationId unknown = NotificationId.newId();
    when(repository.findById(unknown)).thenReturn(Mono.empty());
    final TenantId other = TenantId.of("tenant-2");

    StepVerifier.create(
            service.resend(
                new ResendNotificationCommand(
                    other,
                    foreign.notificationId(),
                    ChannelType.of("SMS"),
                    Recipient.of(PHONE),
                    null)))
        .expectError(NotificationNotFoundException.class)
        .verify();
    StepVerifier.create(
            service.resend(
                new ResendNotificationCommand(
                    other, unknown, ChannelType.of("SMS"), Recipient.of(PHONE), null)))
        .expectError(NotificationNotFoundException.class)
        .verify();

    verify(send, never()).send(any());
  }
}
