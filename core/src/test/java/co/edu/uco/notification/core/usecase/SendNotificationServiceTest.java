package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentNotReadyException;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.exception.NotificationAlreadyAcceptedException;
import co.edu.uco.notification.core.port.in.AttachmentSummary;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationEventPublisherPort;
import co.edu.uco.notification.core.repository.NotificationRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SendNotificationServiceTest {

  private final ChannelCatalogPort channelCatalogPort = Mockito.mock(ChannelCatalogPort.class);
  private final NotificationRepository notificationRepository =
      Mockito.mock(NotificationRepository.class);
  private final NotificationEventPublisherPort eventPublisherPort =
      Mockito.mock(NotificationEventPublisherPort.class);
  private final AttachmentResolver attachmentResolver = Mockito.mock(AttachmentResolver.class);

  private SendNotificationService service;

  private static final TenantId TENANT_ID = TenantId.of("tenant-1");
  private static final ExternalId EXTERNAL_ID = ExternalId.of("order-42");
  private static final ChannelType CHANNEL_TYPE = ChannelType.of("EMAIL");

  @BeforeEach
  void setUp() {
    service =
        new SendNotificationService(
            channelCatalogPort, notificationRepository, eventPublisherPort, attachmentResolver);
    when(attachmentResolver.resolve(any(), any())).thenReturn(Mono.just(List.of()));
  }

  private static SendNotificationCommand command() {
    return new SendNotificationCommand(
        TENANT_ID,
        EXTERNAL_ID,
        CHANNEL_TYPE,
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  private static ChannelRoute activeRoute() {
    return new ChannelRoute(CHANNEL_TYPE, List.of(ProviderId.of("brevo")), null);
  }

  @Test
  void sendAcceptsSavesPublishesAndEnqueuesWhenNoDuplicateExists() {
    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.empty());
    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(any(Notification.class))).thenReturn(Mono.empty());

    final SendNotificationResult result = service.send(command()).block();

    assertNotNull(result);
    assertEquals(NotificationStatus.PENDING, result.status());
    assertFalse(result.duplicate());

    verify(notificationRepository).save(any(Notification.class));
    verify(eventPublisherPort).publish(any());
    verify(eventPublisherPort).enqueueForDispatch(any(Notification.class));
  }

  @Test
  void sendReturnsExistingNotificationWithoutSideEffectsWhenDuplicateExists() {
    final Notification existing =
        Notification.accept(
            new NotificationRouting(
                TENANT_ID,
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    existing.pullEvents();

    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.just(existing));

    final SendNotificationResult result = service.send(command()).block();

    assertNotNull(result);
    assertEquals(existing.notificationId(), result.notificationId());
    assertTrue(result.duplicate());

    verify(notificationRepository, never()).save(any(Notification.class));
    verify(eventPublisherPort, never()).publish(any());
    verify(eventPublisherPort, never()).enqueueForDispatch(any(Notification.class));
  }

  @Test
  void sendReturnsTheExistingNotificationWhenAConcurrentSaveLosesTheIdempotencyRace() {
    final Notification existing =
        Notification.accept(
            new NotificationRouting(
                TENANT_ID,
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    existing.pullEvents();

    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(Mono.just(activeRoute()));
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.empty(), Mono.just(existing));
    when(notificationRepository.save(any(Notification.class)))
        .thenReturn(Mono.error(new NotificationAlreadyAcceptedException(TENANT_ID, EXTERNAL_ID)));

    final SendNotificationResult result = service.send(command()).block();

    assertNotNull(result);
    assertEquals(existing.notificationId(), result.notificationId());
    assertTrue(result.duplicate());

    verify(eventPublisherPort, never()).publish(any());
    verify(eventPublisherPort, never()).enqueueForDispatch(any(Notification.class));
  }

  @Test
  void sendFailsWithChannelNotAvailableWhenNoActiveRoute() {
    when(channelCatalogPort.findActiveRoute(eq(CHANNEL_TYPE), eq(TENANT_ID)))
        .thenReturn(Mono.empty());

    StepVerifier.create(service.send(command()))
        .expectError(ChannelNotAvailableException.class)
        .verify();

    verify(notificationRepository, never())
        .findByTenantAndExternalId(any(TenantId.class), any(ExternalId.class));
  }

  @Test
  void sendAcceptsWhenContentMatchesTheChannelSchema() {
    final ChannelRoute routeWithSchema =
        new ChannelRoute(CHANNEL_TYPE, List.of(ProviderId.of("brevo")), "{\"type\":\"object\"}");
    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(Mono.just(routeWithSchema));
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.empty());
    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(any(Notification.class))).thenReturn(Mono.empty());

    final SendNotificationResult result = service.send(command()).block();

    assertNotNull(result);
    assertFalse(result.duplicate());
  }

  @Test
  void sendFailsWithInvalidContentWhenContentDoesNotMatchTheChannelSchema() {
    final ChannelRoute routeWithSchema =
        new ChannelRoute(
            CHANNEL_TYPE,
            List.of(ProviderId.of("brevo")),
            "{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\",\"minLength\":1}}}");
    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(Mono.just(routeWithSchema));

    StepVerifier.create(service.send(command()))
        .expectError(InvalidContentException.class)
        .verify();

    verify(notificationRepository, never())
        .findByTenantAndExternalId(any(TenantId.class), any(ExternalId.class));
    verify(notificationRepository, never()).save(any(Notification.class));
  }

  @Test
  void sendRejectsNullCommand() {
    assertThrows(NullPointerException.class, () -> service.send(null));
  }

  @Test
  void constructorRejectsNullChannelCatalogPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SendNotificationService(
                null, notificationRepository, eventPublisherPort, attachmentResolver));
  }

  @Test
  void constructorRejectsNullNotificationRepository() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SendNotificationService(
                channelCatalogPort, null, eventPublisherPort, attachmentResolver));
  }

  @Test
  void constructorRejectsNullEventPublisherPort() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SendNotificationService(
                channelCatalogPort, notificationRepository, null, attachmentResolver));
  }

  @Test
  void constructorRejectsNullAttachmentResolver() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SendNotificationService(
                channelCatalogPort, notificationRepository, eventPublisherPort, null));
  }

  private static final String ATTACHMENTS_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"attachments\":{\"type\":\"array\",\"maxItems\":5,"
          + "\"items\":{\"properties\":{\"contentType\":{\"enum\":[\"application/pdf\",\"image/png\"]}}}}}}";

  private static final byte[] PDF_BYTES = "%PDF-1.4 invoice".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PNG_BYTES = "png-receipt".getBytes(StandardCharsets.UTF_8);

  private static final AttachmentSubmission INVOICE_SUBMISSION =
      AttachmentSubmission.embedded(
          "invoice.pdf",
          "application/pdf",
          (long) PDF_BYTES.length,
          Base64.getEncoder().encodeToString(PDF_BYTES));
  private static final AttachmentSubmission RECEIPT_SUBMISSION =
      AttachmentSubmission.embedded(
          "receipt.png",
          "image/png",
          (long) PNG_BYTES.length,
          Base64.getEncoder().encodeToString(PNG_BYTES));

  private static final Attachment INVOICE = verified("invoice.pdf", "application/pdf", PDF_BYTES);
  private static final Attachment RECEIPT = verified("receipt.png", "image/png", PNG_BYTES);

  private static Attachment verified(
      final String fileName, final String contentType, final byte[] bytes) {
    return new Attachment(
        TENANT_ID,
        fileName,
        contentType,
        bytes.length,
        Sha256Digest.of(bytes),
        new AttachmentSource.EmbeddedContent(bytes));
  }

  private static SendNotificationCommand commandWith(final AttachmentSubmission... attachments) {
    return new SendNotificationCommand(
        TENANT_ID,
        EXTERNAL_ID,
        CHANNEL_TYPE,
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Subject", "Body"),
        Priority.NORMAL,
        List.of(attachments));
  }

  private void givenRouteWithSchema(final String schema) {
    when(channelCatalogPort.findActiveRoute(CHANNEL_TYPE, TENANT_ID))
        .thenReturn(
            Mono.just(new ChannelRoute(CHANNEL_TYPE, List.of(ProviderId.of("simulated")), schema)));
  }

  private void givenResolved(final Attachment... attachments) {
    when(attachmentResolver.resolve(eq(TENANT_ID), any()))
        .thenReturn(Mono.just(List.of(attachments)));
  }

  private void givenAcceptancePipeline() {
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.empty());
    when(notificationRepository.save(any(Notification.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(eventPublisherPort.publish(any())).thenReturn(Mono.empty());
    when(eventPublisherPort.enqueueForDispatch(any(Notification.class))).thenReturn(Mono.empty());
  }

  private void verifyNothingWasStored() {
    verify(notificationRepository, never())
        .findByTenantAndExternalId(any(TenantId.class), any(ExternalId.class));
    verify(notificationRepository, never()).save(any(Notification.class));
    verify(eventPublisherPort, never()).publish(any());
    verify(eventPublisherPort, never()).enqueueForDispatch(any(Notification.class));
  }

  @Test
  void sendStoresTheVerifiedAttachmentsInOrderWhenTheChannelDeclaresThem() {
    givenRouteWithSchema(ATTACHMENTS_SCHEMA);
    givenResolved(INVOICE, RECEIPT);
    givenAcceptancePipeline();

    StepVerifier.create(service.send(commandWith(INVOICE_SUBMISSION, RECEIPT_SUBMISSION)))
        .assertNext(
            result -> {
              assertFalse(result.duplicate());
              assertEquals(
                  List.of(
                      new AttachmentSummary(
                          "invoice.pdf",
                          "application/pdf",
                          PDF_BYTES.length,
                          Sha256Digest.of(PDF_BYTES).hex()),
                      new AttachmentSummary(
                          "receipt.png",
                          "image/png",
                          PNG_BYTES.length,
                          Sha256Digest.of(PNG_BYTES).hex())),
                  result.attachments());
            })
        .verifyComplete();

    verify(attachmentResolver).resolve(TENANT_ID, List.of(INVOICE_SUBMISSION, RECEIPT_SUBMISSION));
    final ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(saved.capture());
    assertEquals(List.of(INVOICE, RECEIPT), saved.getValue().content().attachments());
    assertEquals("Subject", saved.getValue().content().subject());
    assertEquals("Body", saved.getValue().content().body());
    verify(eventPublisherPort).enqueueForDispatch(any(Notification.class));
  }

  @Test
  void sendReturnsTheOriginalForAValidDuplicateWithAttachments() {
    final Notification existing =
        Notification.accept(
            new NotificationRouting(
                TENANT_ID,
                EXTERNAL_ID,
                CHANNEL_TYPE,
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(
                NotificationContent.of("Subject", "Body", List.of(INVOICE)), Priority.NORMAL));
    existing.pullEvents();
    givenRouteWithSchema(ATTACHMENTS_SCHEMA);
    givenResolved(RECEIPT);
    when(notificationRepository.findByTenantAndExternalId(TENANT_ID, EXTERNAL_ID))
        .thenReturn(Mono.just(existing));

    StepVerifier.create(service.send(commandWith(RECEIPT_SUBMISSION)))
        .assertNext(
            result -> {
              assertTrue(result.duplicate());
              assertEquals(existing.notificationId(), result.notificationId());
              assertEquals(
                  List.of("invoice.pdf"),
                  result.attachments().stream().map(AttachmentSummary::fileName).toList());
            })
        .verifyComplete();

    verify(notificationRepository, never()).save(any(Notification.class));
    assertEquals(List.of(INVOICE), existing.content().attachments());
  }

  @Test
  void theGlobalPolicyRunsBeforeTheChannelSchemaAndTheResolver() {
    givenRouteWithSchema(null);
    final AttachmentSubmission gif =
        AttachmentSubmission.embedded("anim.gif", "image/gif", 3L, "cGRm");

    StepVerifier.create(service.send(commandWith(INVOICE_SUBMISSION, gif)))
        .expectErrorSatisfies(
            error -> {
              assertInstanceOf(InvalidAttachmentException.class, error);
              assertTrue(error.getMessage().startsWith("attachments[1]: "), error.getMessage());
            })
        .verify();

    verify(attachmentResolver, never()).resolve(any(), any());
    verifyNothingWasStored();
  }

  @Test
  void theChannelSchemaRunsBeforeTheResolver() {
    givenRouteWithSchema(ATTACHMENTS_SCHEMA);
    final byte[] jpeg = "jpeg".getBytes(StandardCharsets.UTF_8);
    final AttachmentSubmission photo =
        AttachmentSubmission.embedded(
            "photo.jpg",
            "image/jpeg",
            (long) jpeg.length,
            Base64.getEncoder().encodeToString(jpeg));

    StepVerifier.create(service.send(commandWith(photo)))
        .expectError(InvalidContentException.class)
        .verify();

    verify(attachmentResolver, never()).resolve(any(), any());
    verifyNothingWasStored();
  }

  @Test
  void sendRejectsAttachmentsOnAChannelThatDoesNotDeclareThem() {
    givenRouteWithSchema(null);

    StepVerifier.create(service.send(commandWith(INVOICE_SUBMISSION)))
        .expectErrorSatisfies(
            error ->
                assertTrue(
                    error.getMessage().contains("channel does not accept attachments"),
                    error.getMessage()))
        .verify();

    verifyNothingWasStored();
  }

  @Test
  void aResolverRejectionHappensBeforeLookingForTheDuplicateAndStoresNothing() {
    givenRouteWithSchema(ATTACHMENTS_SCHEMA);
    when(attachmentResolver.resolve(any(), any()))
        .thenReturn(
            Mono.error(
                new InvalidAttachmentException(
                    0, "the file contains malicious software", "invoice.pdf")));

    StepVerifier.create(service.send(commandWith(INVOICE_SUBMISSION)))
        .expectError(InvalidAttachmentException.class)
        .verify();

    verifyNothingWasStored();
  }

  @Test
  void anUploadStillBeingScannedIsRejectedWithoutStoringAnything() {
    givenRouteWithSchema(ATTACHMENTS_SCHEMA);
    when(attachmentResolver.resolve(any(), any()))
        .thenReturn(Mono.error(new AttachmentNotReadyException(0)));

    StepVerifier.create(service.send(commandWith(INVOICE_SUBMISSION)))
        .expectError(AttachmentNotReadyException.class)
        .verify();

    verifyNothingWasStored();
  }

  @Test
  void anUnavailableInspectionPropagatesWithoutStoringAnything() {
    givenRouteWithSchema(ATTACHMENTS_SCHEMA);
    when(attachmentResolver.resolve(any(), any()))
        .thenReturn(
            Mono.error(new AttachmentInspectionUnavailableException("clamav is not reachable")));

    StepVerifier.create(service.send(commandWith(INVOICE_SUBMISSION)))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();

    verifyNothingWasStored();
  }
}
