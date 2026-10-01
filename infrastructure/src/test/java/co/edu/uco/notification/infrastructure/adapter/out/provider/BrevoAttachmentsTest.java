package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.infrastructure.config.BrevoProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class BrevoAttachmentsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final String KEY = "tenants/tenant-1/clean/u-1";

  private static FakeProviderServer fakeServer;
  private static WebClient webClient;

  private final AttachmentStoragePort storage = mock(AttachmentStoragePort.class);
  private BrevoNotificationProvider provider;

  @BeforeAll
  static void startServer() {
    fakeServer = FakeProviderServer.start();
    webClient = WebClient.builder().baseUrl(fakeServer.baseUrl()).build();
  }

  @AfterAll
  static void stopServer() {
    fakeServer.stop();
  }

  @BeforeEach
  void setUp() {
    fakeServer.reset();
    provider =
        new BrevoNotificationProvider(
            webClient,
            new BrevoProviderProperties(
                "test-api-key",
                "sender@example.com",
                "Notification UCO",
                fakeServer.baseUrl(),
                10_000L,
                5_000L),
            new AttachmentContentLoader(storage));
  }

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static Attachment embedded(final String fileName, final byte[] content, final long size) {
    return new Attachment(
        TENANT,
        fileName,
        "text/plain",
        size,
        Sha256Digest.of(content),
        new AttachmentSource.EmbeddedContent(content));
  }

  private static Attachment stored(final byte[] content, final Sha256Digest digest) {
    return new Attachment(
        TENANT,
        "reporte.pdf",
        "application/pdf",
        content.length,
        digest,
        new AttachmentSource.StoredObject(UploadId.newId(), KEY));
  }

  private static Notification notificationWith(final List<Attachment> attachments) {
    return Notification.accept(
        new NotificationRouting(
            TENANT,
            ExternalId.of("order-" + UUID.randomUUID()),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(
            NotificationContent.of("Subject", "Body", attachments), Priority.NORMAL));
  }

  private JsonNode sentBody() throws Exception {
    assertEquals(1, fakeServer.requests().size());
    return MAPPER.readTree(fakeServer.requests().get(0).body());
  }

  @Test
  void anEmbeddedAttachmentIsSentInBase64WithItsFileName() throws Exception {
    final byte[] content = bytes("hola adjunto");

    StepVerifier.create(
            provider.send(notificationWith(List.of(embedded("nota.txt", content, content.length)))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final JsonNode attachment = sentBody().get("attachment");
    assertEquals(1, attachment.size());
    assertEquals("nota.txt", attachment.get(0).get("name").asText());
    assertEquals(
        Base64.getEncoder().encodeToString(content), attachment.get(0).get("content").asText());
  }

  @Test
  void aStoredAttachmentIsReadFromTheStorageAndSent() throws Exception {
    final byte[] content = bytes("%PDF-contenido");
    when(storage.stat(KEY)).thenReturn(Mono.just(new StoredObjectInfo(content.length, "etag-1")));
    when(storage.read(KEY, "etag-1")).thenReturn(Mono.just(content));

    StepVerifier.create(
            provider.send(notificationWith(List.of(stored(content, Sha256Digest.of(content))))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final JsonNode attachment = sentBody().get("attachment");
    assertEquals("reporte.pdf", attachment.get(0).get("name").asText());
    assertEquals(
        Base64.getEncoder().encodeToString(content), attachment.get(0).get("content").asText());
  }

  @Test
  void severalAttachmentsKeepTheirOrder() throws Exception {
    final byte[] first = bytes("uno");
    final byte[] second = bytes("dos");

    StepVerifier.create(
            provider.send(
                notificationWith(
                    List.of(
                        embedded("a.txt", first, first.length),
                        embedded("b.txt", second, second.length)))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    final JsonNode attachment = sentBody().get("attachment");
    assertEquals("a.txt", attachment.get(0).get("name").asText());
    assertEquals("b.txt", attachment.get(1).get("name").asText());
  }

  @Test
  void aNotificationWithoutAttachmentsDoesNotSendTheField() throws Exception {
    StepVerifier.create(provider.send(notificationWith(List.of())))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertFalse(sentBody().has("attachment"));
    verify(storage, never()).stat(anyString());
  }

  @Test
  void attachmentsOverBrevoLimitAreRejectedBeforeAnyCall() {
    final byte[] content = bytes("x");

    StepVerifier.create(
            provider.send(
                notificationWith(
                    List.of(
                        embedded(
                            "grande.txt",
                            content,
                            BrevoNotificationProvider.MAX_ATTACHMENTS_BYTES + 1)))))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void theSumOfSeveralAttachmentsCountsAgainstBrevoLimit() {
    final byte[] content = bytes("x");
    final long half = BrevoNotificationProvider.MAX_ATTACHMENTS_BYTES / 2 + 1;

    StepVerifier.create(
            provider.send(
                notificationWith(
                    List.of(embedded("a.txt", content, half), embedded("b.txt", content, half)))))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void attachmentsExactlyAtBrevoLimitAreSent() {
    final byte[] content = bytes("x");

    StepVerifier.create(
            provider.send(
                notificationWith(
                    List.of(
                        embedded(
                            "limite.txt",
                            content,
                            BrevoNotificationProvider.MAX_ATTACHMENTS_BYTES)))))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, fakeServer.requests().size());
  }

  @Test
  void aStoredAttachmentThatNoLongerExistsFailsPermanentlyWithoutCalling() {
    final byte[] content = bytes("contenido");
    when(storage.stat(KEY)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.send(notificationWith(List.of(stored(content, Sha256Digest.of(content))))))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void aStoredAttachmentThatChangedFailsPermanentlyWithoutCalling() {
    final byte[] content = bytes("contenido");
    when(storage.stat(KEY)).thenReturn(Mono.just(new StoredObjectInfo(content.length, "etag-1")));
    when(storage.read(KEY, "etag-1"))
        .thenReturn(Mono.error(new AttachmentObjectChangedException()));

    StepVerifier.create(
            provider.send(notificationWith(List.of(stored(content, Sha256Digest.of(content))))))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void anAttachmentThatDoesNotMatchItsDigestFailsPermanentlyWithoutCalling() {
    final byte[] content = bytes("contenido");
    final Sha256Digest other = Sha256Digest.of(bytes("otro contenido"));
    when(storage.stat(KEY)).thenReturn(Mono.just(new StoredObjectInfo(content.length, "etag-1")));
    when(storage.read(KEY, "etag-1")).thenReturn(Mono.just(content));

    StepVerifier.create(provider.send(notificationWith(List.of(stored(content, other)))))
        .expectNext(AttemptResult.PERMANENT_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void anUnavailableStorageIsARecoverableFailureWithoutCalling() {
    final byte[] content = bytes("contenido");
    when(storage.stat(KEY))
        .thenReturn(
            Mono.error(
                new AttachmentInspectionUnavailableException(
                    "the file storage is not available", new IllegalStateException("down"))));

    StepVerifier.create(
            provider.send(notificationWith(List.of(stored(content, Sha256Digest.of(content))))))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();

    assertTrue(fakeServer.requests().isEmpty());
  }

  @Test
  void brevoReportsItSupportsAttachments() {
    assertTrue(provider.supportsAttachments());
  }
}
