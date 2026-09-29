package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentNotReadyException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AttachmentResolverTest {

  private static final TenantId TENANT_A = TenantId.of("tenant-a");
  private static final TenantId TENANT_B = TenantId.of("tenant-b");
  private static final String SECRET = "sig-secret-8080";
  private static final byte[] BYTES = "%PDF-1.4 small".getBytes(StandardCharsets.UTF_8);
  private static final String CONTENT = Base64.getEncoder().encodeToString(BYTES);
  private static final UploadId UPLOAD_ID = UploadId.of("upload-1");
  private static final long LARGE = 2_000_000L;
  private static final Sha256Digest LARGE_SHA =
      Sha256Digest.of("large".getBytes(StandardCharsets.UTF_8));
  private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

  private AttachmentInspector inspector;
  private AttachmentUploadRepository uploads;
  private AttachmentStoragePort storage;
  private AttachmentResolver resolver;

  @BeforeEach
  void setUp() {
    inspector = mock(AttachmentInspector.class);
    uploads = mock(AttachmentUploadRepository.class);
    storage = mock(AttachmentStoragePort.class);
    resolver = new AttachmentResolver(inspector, uploads, storage);
    when(inspector.inspect(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                Mono.just(AttachmentInspection.clean(Sha256Digest.of(invocation.getArgument(3)))));
    when(storage.parseUploadUrl(any()))
        .thenAnswer(
            invocation -> {
              final String url = invocation.getArgument(0);
              return url.startsWith("http://minio.test/bucket/")
                  ? Optional.of(url.substring("http://minio.test/bucket/".length()).split("\\?")[0])
                  : Optional.empty();
            });
    when(uploads.findByTenantAndId(any(), any())).thenReturn(Mono.empty());
  }

  private static AttachmentSubmission embedded(final String fileName) {
    return AttachmentSubmission.embedded(fileName, "application/pdf", (long) BYTES.length, CONTENT);
  }

  private static String urlFor(final TenantId tenant, final UploadId uploadId) {
    return "http://minio.test/bucket/"
        + AttachmentUpload.uploadKeyFor(tenant, uploadId)
        + "?X-Amz-Signature="
        + SECRET;
  }

  private static AttachmentSubmission reference(final TenantId urlTenant) {
    return AttachmentSubmission.reference(
        "contract.pdf", "application/pdf", LARGE, urlFor(urlTenant, UPLOAD_ID));
  }

  private static AttachmentUpload upload(final TenantId tenant) {
    return AttachmentUpload.issue(
        UPLOAD_ID, tenant, "contract.pdf", "application/pdf", LARGE, NOW, NOW.plusSeconds(900));
  }

  private List<Attachment> resolve(
      final TenantId tenant, final AttachmentSubmission... submissions) {
    return resolver.resolve(tenant, List.of(submissions)).block();
  }

  private <T extends Throwable> T rejected(
      final Class<T> type, final TenantId tenant, final AttachmentSubmission... submissions) {
    final T exception = assertThrows(type, () -> resolve(tenant, submissions));
    assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
    assertFalse(exception.getMessage().contains(CONTENT), exception.getMessage());
    return exception;
  }

  @Test
  void resolvesACleanEmbeddedFileIntoAVerifiedAttachment() {
    final Attachment attachment = resolve(TENANT_A, embedded("invoice.pdf")).getFirst();

    assertEquals(TENANT_A, attachment.tenantId());
    assertEquals("invoice.pdf", attachment.fileName());
    assertEquals(BYTES.length, attachment.sizeBytes());
    assertEquals(Sha256Digest.of(BYTES), attachment.sha256());
    final AttachmentSource.EmbeddedContent source =
        assertInstanceOf(AttachmentSource.EmbeddedContent.class, attachment.source());
    assertArrayEquals(BYTES, source.bytes());
    verify(inspector).inspect(TENANT_A, "invoice.pdf", "application/pdf", BYTES);
  }

  @Test
  void rejectsAnEmbeddedFileWithMalware() {
    doReturn(
            Mono.just(
                AttachmentInspection.rejected(
                    Sha256Digest.of(BYTES),
                    AttachmentRejectionReason.MALWARE,
                    "Eicar-Test-Signature",
                    "text/plain")))
        .when(inspector)
        .inspect(any(), any(), any(), any());

    final InvalidAttachmentException exception =
        rejected(InvalidAttachmentException.class, TENANT_A, embedded("eicar.txt"));

    assertEquals(
        "attachments[0]: the file contains malicious software (eicar.txt)", exception.getMessage());
  }

  @Test
  void rejectsAnEmbeddedFileWhoseRealTypeDiffers() {
    doReturn(
            Mono.just(
                AttachmentInspection.rejected(
                    Sha256Digest.of(BYTES),
                    AttachmentRejectionReason.CONTENT_TYPE_MISMATCH,
                    null,
                    "image/png")))
        .when(inspector)
        .inspect(any(), any(), any(), any());

    final InvalidAttachmentException exception =
        rejected(InvalidAttachmentException.class, TENANT_A, embedded("invoice.pdf"));

    assertEquals(
        "attachments[0]: the content is not application/pdf (invoice.pdf)", exception.getMessage());
  }

  @Test
  void resolvesACleanUploadOfTheSameTenantIntoAStoredObject() {
    when(uploads.findByTenantAndId(TENANT_A, UPLOAD_ID))
        .thenReturn(Mono.just(upload(TENANT_A).markClean(LARGE_SHA, NOW)));

    final Attachment attachment = resolve(TENANT_A, reference(TENANT_A)).getFirst();

    assertEquals(TENANT_A, attachment.tenantId());
    assertEquals(LARGE_SHA, attachment.sha256());
    assertEquals(LARGE, attachment.sizeBytes());
    assertEquals(
        new AttachmentSource.StoredObject(UPLOAD_ID, "tenants/tenant-a/clean/upload-1"),
        attachment.source());
    verify(inspector, never()).inspect(any(), any(), any(), any());
  }

  @Test
  void rejectsAnUploadStillPendingScanAsNotReady() {
    when(uploads.findByTenantAndId(TENANT_A, UPLOAD_ID)).thenReturn(Mono.just(upload(TENANT_A)));

    final AttachmentNotReadyException exception =
        rejected(
            AttachmentNotReadyException.class, TENANT_A, embedded("a.pdf"), reference(TENANT_A));

    assertEquals(1, exception.position());
  }

  @Test
  void rejectsAnInfectedUpload() {
    when(uploads.findByTenantAndId(TENANT_A, UPLOAD_ID))
        .thenReturn(
            Mono.just(
                upload(TENANT_A)
                    .markInfected(LARGE_SHA, AttachmentRejectionReason.MALWARE, "Eicar", NOW)));

    final InvalidAttachmentException exception =
        rejected(InvalidAttachmentException.class, TENANT_A, reference(TENANT_A));

    assertEquals(
        "attachments[0]: the upload was rejected by the scan (contract.pdf)",
        exception.getMessage());
  }

  @Test
  void anUnknownUploadAnotherTenantsUploadAndAForeignUrlAreRejectedIdentically() {
    when(uploads.findByTenantAndId(TENANT_A, UPLOAD_ID))
        .thenReturn(Mono.just(upload(TENANT_A).markClean(LARGE_SHA, NOW)));

    final String unknown =
        rejected(
                InvalidAttachmentException.class,
                TENANT_B,
                AttachmentSubmission.reference(
                    "contract.pdf",
                    "application/pdf",
                    LARGE,
                    urlFor(TENANT_B, UploadId.of("never-issued"))))
            .getMessage();
    final String otherTenantsUrl =
        rejected(InvalidAttachmentException.class, TENANT_B, reference(TENANT_A)).getMessage();
    final String foreign =
        rejected(
                InvalidAttachmentException.class,
                TENANT_B,
                AttachmentSubmission.reference(
                    "contract.pdf",
                    "application/pdf",
                    LARGE,
                    "https://files.example.test/" + SECRET + "/contract.pdf"))
            .getMessage();

    final String expected =
        "attachments[0]: url is not an upload issued by this service for this tenant"
            + " (contract.pdf)";
    assertEquals(expected, unknown);
    assertEquals(expected, otherTenantsUrl);
    assertEquals(expected, foreign);
    verify(uploads, never()).findByTenantAndId(TENANT_A, UPLOAD_ID);
  }

  @Test
  void neverLooksUpAnUploadOfAnotherTenantEvenWithTheSameId() {
    when(uploads.findByTenantAndId(TENANT_A, UPLOAD_ID))
        .thenReturn(Mono.just(upload(TENANT_A).markClean(LARGE_SHA, NOW)));

    rejected(
        InvalidAttachmentException.class,
        TENANT_B,
        AttachmentSubmission.reference(
            "contract.pdf", "application/pdf", LARGE, urlFor(TENANT_B, UPLOAD_ID)));

    verify(uploads).findByTenantAndId(TENANT_B, UPLOAD_ID);
    verify(uploads, never()).findByTenantAndId(TENANT_A, UPLOAD_ID);
  }

  @Test
  void discardsAnUploadOfAnotherTenantEvenIfTheRepositoryReturnedIt() {
    when(uploads.findByTenantAndId(TENANT_B, UPLOAD_ID))
        .thenReturn(Mono.just(upload(TENANT_A).markClean(LARGE_SHA, NOW)));

    final InvalidAttachmentException exception =
        rejected(
            InvalidAttachmentException.class,
            TENANT_B,
            AttachmentSubmission.reference(
                "contract.pdf", "application/pdf", LARGE, urlFor(TENANT_B, UPLOAD_ID)));

    assertTrue(exception.getMessage().contains("url is not an upload issued"));
    assertFalse(exception.getMessage().contains("tenant-a"), exception.getMessage());
  }

  @Test
  void rejectsMetadataThatDoesNotMatchTheUpload() {
    when(uploads.findByTenantAndId(TENANT_A, UPLOAD_ID))
        .thenReturn(Mono.just(upload(TENANT_A).markClean(LARGE_SHA, NOW)));

    final String url = urlFor(TENANT_A, UPLOAD_ID);
    for (final AttachmentSubmission mismatch :
        List.of(
            AttachmentSubmission.reference("other.pdf", "application/pdf", LARGE, url),
            AttachmentSubmission.reference("contract.pdf", "text/plain", LARGE, url),
            AttachmentSubmission.reference("contract.pdf", "application/pdf", LARGE + 1, url))) {
      final InvalidAttachmentException exception =
          rejected(InvalidAttachmentException.class, TENANT_A, mismatch);
      assertTrue(
          exception
              .getMessage()
              .contains("fileName, contentType and sizeBytes must match the upload"),
          exception.getMessage());
    }
  }

  @Test
  void keepsTheOrderOfTheSubmissions() {
    when(uploads.findByTenantAndId(TENANT_A, UPLOAD_ID))
        .thenReturn(Mono.just(upload(TENANT_A).markClean(LARGE_SHA, NOW)));

    final List<Attachment> attachments =
        resolve(TENANT_A, embedded("first.pdf"), reference(TENANT_A), embedded("third.pdf"));

    assertEquals(
        List.of("first.pdf", "contract.pdf", "third.pdf"),
        attachments.stream().map(Attachment::fileName).toList());
  }

  @Test
  void resolvesAnEmptyListWithoutTouchingAnyPort() {
    assertEquals(List.of(), resolve(TENANT_A));
    verify(inspector, never()).inspect(any(), any(), any(), any());
  }
}
