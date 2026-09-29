package co.edu.uco.notification.infrastructure.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.out.PresignedUpload;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.infrastructure.support.AttachmentTestContainers;
import co.edu.uco.notification.infrastructure.support.SampleFiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class MinioAttachmentStorageAdapterTest {

  private static AttachmentProperties properties(final String bucket, final String endpoint) {
    return new AttachmentProperties(
        new AttachmentProperties.Scan(Duration.ofSeconds(10), Duration.ofHours(24), 2, 3),
        new AttachmentProperties.Upload(Duration.ofMinutes(15)),
        new AttachmentProperties.Storage(
            bucket,
            endpoint,
            endpoint,
            AttachmentTestContainers.MINIO_USER,
            AttachmentTestContainers.MINIO_PASSWORD),
        new AttachmentProperties.ClamAv("localhost", 3310, 2));
  }

  private static MinioAttachmentStorageAdapter adapter(final String bucket) {
    return new MinioAttachmentStorageAdapter(
        properties(bucket, AttachmentTestContainers.minioEndpoint()));
  }

  private static int put(final String url, final byte[] content)
      throws IOException, InterruptedException {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private static String freshKey() {
    return "tenants/tenant-1/uploads/" + UUID.randomUUID();
  }

  @Test
  void aPresignedUploadAcceptsARealPutAndTheObjectCanBeStatAndRead() throws Exception {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = freshKey();
    final byte[] content = SampleFiles.pdfOfSize(1_500_000);

    final PresignedUpload upload = adapter.presignUpload(key, Duration.ofMinutes(15)).block();

    assertTrue(upload.url().startsWith(AttachmentTestContainers.minioEndpoint()), upload.url());
    assertTrue(upload.url().contains("X-Amz-Signature"), upload.url());
    assertTrue(upload.expiresAt().isAfter(Instant.now().plusSeconds(800)));
    assertEquals(200, put(upload.url(), content));
    final StoredObjectInfo info = adapter.stat(key).block();
    assertEquals(content.length, info.sizeBytes());
    assertArrayEquals(content, adapter.read(key, info.etag()).block());
  }

  @Test
  void statOfAMissingObjectIsEmpty() {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    adapter.presignUpload(freshKey(), Duration.ofMinutes(1)).block();

    assertNull(adapter.stat(freshKey()).block());
  }

  @Test
  void copyIfMatchCopiesTheScannedVersionAndDeleteRemovesIt() throws Exception {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = freshKey();
    final String cleanKey = key.replace("/uploads/", "/clean/");
    assertEquals(
        200,
        put(
            adapter.presignUpload(key, Duration.ofMinutes(5)).block().url(),
            SampleFiles.pdf("v1")));
    final String etag = adapter.stat(key).block().etag();

    adapter.copyIfMatch(key, cleanKey, etag).block();
    adapter.delete(key).block();

    assertArrayEquals(
        SampleFiles.pdf("v1"),
        adapter.read(cleanKey, adapter.stat(cleanKey).block().etag()).block());
    assertNull(adapter.stat(key).block());
  }

  @Test
  void anObjectReplacedAfterTheScanIsNeitherCopiedNorRead() throws Exception {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = freshKey();
    final String url = adapter.presignUpload(key, Duration.ofMinutes(5)).block().url();
    assertEquals(200, put(url, SampleFiles.pdf("scanned")));
    final String scannedEtag = adapter.stat(key).block().etag();
    assertEquals(200, put(url, SampleFiles.pdf("replaced after the scan")));

    StepVerifier.create(adapter.copyIfMatch(key, key.replace("/uploads/", "/clean/"), scannedEtag))
        .expectError(AttachmentObjectChangedException.class)
        .verify();
    StepVerifier.create(adapter.read(key, scannedEtag))
        .expectError(AttachmentObjectChangedException.class)
        .verify();
    assertNull(adapter.stat(key.replace("/uploads/", "/clean/")).block());
  }

  @Test
  void parseUploadUrlAcceptsOnlyItsOwnEndpointAndBucket() {
    final String endpoint = AttachmentTestContainers.minioEndpoint();
    final MinioAttachmentStorageAdapter adapter = adapter("attachments");

    assertEquals(
        Optional.of("tenants/t-1/uploads/abc"),
        adapter.parseUploadUrl(
            endpoint + "/attachments/tenants/t-1/uploads/abc?X-Amz-Signature=s"));
    assertEquals(
        Optional.of("tenants/a%2Fb/uploads/abc"),
        adapter.parseUploadUrl(endpoint + "/attachments/tenants/a%252Fb/uploads/abc"));
    assertEquals(
        Optional.empty(),
        adapter.parseUploadUrl("https://files.example.test/attachments/tenants/t-1/uploads/abc"));
    assertEquals(
        Optional.empty(),
        adapter.parseUploadUrl(
            endpoint.replace(
                    String.valueOf(AttachmentTestContainers.minio().getMappedPort(9000)), "1")
                + "/attachments/tenants/t-1/uploads/abc"));
    assertEquals(
        Optional.empty(),
        adapter.parseUploadUrl(endpoint + "/other-bucket/tenants/t-1/uploads/abc"));
    assertEquals(Optional.empty(), adapter.parseUploadUrl(endpoint + "/attachments/"));
    assertEquals(Optional.empty(), adapter.parseUploadUrl("http://[::1"));
    assertEquals(Optional.empty(), adapter.parseUploadUrl(null));
    assertEquals(
        Optional.empty(),
        adapter.parseUploadUrl(
            endpoint.replace("http://", "ftp://") + "/attachments/tenants/t/uploads/a"));
  }

  @Test
  void anUnreachableStorageIsReportedAsUnavailable() {
    final MinioAttachmentStorageAdapter adapter =
        new MinioAttachmentStorageAdapter(properties("bucket", "http://localhost:1"));

    StepVerifier.create(adapter.stat("tenants/t/uploads/x"))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify(Duration.ofSeconds(30));
    StepVerifier.create(adapter.presignUpload("tenants/t/uploads/x", Duration.ofMinutes(1)))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify(Duration.ofSeconds(30));
  }

  @Test
  void aStorageWithoutCredentialsIsReportedAsUnavailableInsteadOfFailingAtStartup() {
    final MinioAttachmentStorageAdapter adapter =
        new MinioAttachmentStorageAdapter(
            new AttachmentProperties(
                new AttachmentProperties.Scan(Duration.ofSeconds(10), Duration.ofHours(24), 2, 3),
                new AttachmentProperties.Upload(Duration.ofMinutes(15)),
                new AttachmentProperties.Storage(
                    "bucket", "http://localhost:9000", "http://localhost:9000", "", null),
                new AttachmentProperties.ClamAv("localhost", 3310, 2)));

    StepVerifier.create(adapter.stat("tenants/t/uploads/x"))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();
    StepVerifier.create(adapter.presignUpload("tenants/t/uploads/x", Duration.ofMinutes(1)))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();
    assertEquals(
        Optional.of("tenants/t/uploads/x"),
        adapter.parseUploadUrl("http://localhost:9000/bucket/tenants/t/uploads/x"));
  }

  @Test
  void presignedUploadToStringHidesTheUrl() {
    final String text =
        new PresignedUpload("http://minio/b/k?X-Amz-Signature=secret-1", Instant.now()).toString();

    assertFalse(text.contains("secret-1"), text);
  }

  @Test
  void pingIsTrueOnlyWhenTheStorageAnswers() {
    assertTrue(adapter("bucket-" + UUID.randomUUID()).ping().block());
    assertFalse(
        new MinioAttachmentStorageAdapter(properties("bucket", "http://localhost:1"))
            .ping()
            .block());
  }
}
