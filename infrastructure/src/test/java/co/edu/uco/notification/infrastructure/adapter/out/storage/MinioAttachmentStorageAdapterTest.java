package co.edu.uco.notification.infrastructure.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.out.PresignedUpload;
import co.edu.uco.notification.core.port.out.StoredObjectInfo;
import co.edu.uco.notification.infrastructure.config.AttachmentProperties;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.AttachmentTestContainers;
import co.edu.uco.notification.infrastructure.support.SampleFiles;
import io.minio.GetBucketLifecycleArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
        properties(bucket, AttachmentTestContainers.minioEndpoint()), Clock.systemUTC());
  }

  private static MinioAttachmentStorageAdapter unreachable(final String bucket) {
    return new MinioAttachmentStorageAdapter(
        properties(bucket, "http://localhost:1"), Clock.systemUTC());
  }

  private static PresignedUpload presign(
      final MinioAttachmentStorageAdapter adapter, final String key, final byte[] content) {
    return adapter.presignUpload(key, PDF, content.length, Duration.ofMinutes(15)).block();
  }

  private static int upload(final PresignedUpload presigned, final byte[] content)
      throws IOException, InterruptedException {
    return postForm(presigned, Map.of(), PDF, content);
  }

  private static int postForm(
      final PresignedUpload presigned,
      final Map<String, String> overrides,
      final String fileContentType,
      final byte[] content)
      throws IOException, InterruptedException {
    final String boundary = "----form" + UUID.randomUUID();
    final Map<String, String> fields = new LinkedHashMap<>(presigned.fields());
    fields.putAll(overrides);
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (final Map.Entry<String, String> field : fields.entrySet()) {
      body.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + field.getKey()
                  + "\"\r\n\r\n"
                  + field.getValue()
                  + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    body.writeBytes(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n"
                + "Content-Type: "
                + fileContentType
                + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.writeBytes(content);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(presigned.url()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private static final String PDF = "application/pdf";

  private static String freshKey() {
    return "uploads/tenant-1/" + UUID.randomUUID();
  }

  @Test
  void aSignedFormAcceptsARealPostAndTheObjectCanBeStatAndRead() throws Exception {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = freshKey();
    final byte[] content = SampleFiles.pdfOfSize(1_500_000);

    final PresignedUpload upload = presign(adapter, key, content);

    assertTrue(upload.url().startsWith(AttachmentTestContainers.minioEndpoint()), upload.url());
    assertTrue(upload.fields().containsKey("x-amz-signature"), upload.fields().keySet().toString());
    assertTrue(upload.expiresAt().isAfter(Instant.now().plusSeconds(800)));
    assertEquals(204, upload(upload, content));
    final StoredObjectInfo info = adapter.stat(key).block();
    assertEquals(content.length, info.sizeBytes());
    assertArrayEquals(content, adapter.read(key, info.etag()).block());
  }

  @Test
  void statOfAMissingObjectIsEmpty() {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    presign(adapter, freshKey(), new byte[10]);

    assertNull(adapter.stat(freshKey()).block());
  }

  @Test
  void copyIfMatchCopiesTheScannedVersionAndDeleteRemovesIt() throws Exception {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = freshKey();
    final String cleanKey = "tenants/tenant-1/clean/" + UUID.randomUUID();
    assertEquals(204, upload(presign(adapter, key, SampleFiles.pdf("v1")), SampleFiles.pdf("v1")));
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
    final String cleanKey = "tenants/tenant-1/clean/" + UUID.randomUUID();
    final PresignedUpload first = presign(adapter, key, SampleFiles.pdf("scanned"));
    assertEquals(204, upload(first, SampleFiles.pdf("scanned")));
    final String scannedEtag = adapter.stat(key).block().etag();
    final PresignedUpload second =
        presign(adapter, key, SampleFiles.pdf("replaced after the scan"));
    assertEquals(204, upload(second, SampleFiles.pdf("replaced after the scan")));

    StepVerifier.create(adapter.copyIfMatch(key, cleanKey, scannedEtag))
        .expectError(AttachmentObjectChangedException.class)
        .verify();
    StepVerifier.create(adapter.read(key, scannedEtag))
        .expectError(AttachmentObjectChangedException.class)
        .verify();
    assertNull(adapter.stat(cleanKey).block());
  }

  @Test
  void theStoreRejectsAnObjectOfAnotherSizeThanDeclared() throws Exception {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = freshKey();
    final byte[] declared = SampleFiles.pdfOfSize(1_200_000);
    final PresignedUpload presigned = presign(adapter, key, declared);

    assertEquals(400, upload(presigned, SampleFiles.pdfOfSize(1_200_001)));
    assertEquals(400, upload(presigned, SampleFiles.pdfOfSize(1_199_999)));
    assertNull(adapter.stat(key).block());
    assertEquals(204, upload(presigned, declared));
    assertEquals(declared.length, adapter.stat(key).block().sizeBytes());
  }

  @Test
  void theStoreRejectsAnObjectOfAnotherTypeOrKeyThanSigned() throws Exception {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = freshKey();
    final byte[] content = SampleFiles.pdfOfSize(1_200_000);
    final PresignedUpload presigned = presign(adapter, key, content);

    assertEquals(403, postForm(presigned, Map.of("Content-Type", "text/html"), PDF, content));
    assertEquals(403, postForm(presigned, Map.of("key", "uploads/tenant-2/other"), PDF, content));
    assertNull(adapter.stat(key).block());
    assertNull(adapter.stat("uploads/tenant-2/other").block());
    assertEquals(204, upload(presigned, content));
  }

  @Test
  void anExpiredFormIsRejectedByTheStore() throws Exception {
    final MinioAttachmentStorageAdapter adapter =
        new MinioAttachmentStorageAdapter(
            properties("bucket-" + UUID.randomUUID(), AttachmentTestContainers.minioEndpoint()),
            Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
    final byte[] content = SampleFiles.pdfOfSize(1_200_000);

    final PresignedUpload presigned = presign(adapter, freshKey(), content);

    assertEquals(403, upload(presigned, content));
  }

  @Test
  void theFormExpiryUsesTheInjectedClock() {
    final Instant fixed = Instant.parse("2026-10-03T10:00:00Z");
    final MinioAttachmentStorageAdapter adapter =
        new MinioAttachmentStorageAdapter(
            properties("bucket-" + UUID.randomUUID(), AttachmentTestContainers.minioEndpoint()),
            Clock.fixed(fixed, ZoneOffset.UTC));

    final PresignedUpload presigned = presign(adapter, freshKey(), new byte[1_200_000]);

    assertEquals(fixed.plus(Duration.ofMinutes(15)), presigned.expiresAt());
  }

  @Test
  void theAttachmentUrlHasNoSignatureAndIsParsedBackToTheKey() {
    final MinioAttachmentStorageAdapter adapter = adapter("bucket-" + UUID.randomUUID());
    final String key = "uploads/a%2Fb/" + UUID.randomUUID();

    final PresignedUpload presigned = presign(adapter, key, new byte[1_200_000]);

    assertFalse(presigned.attachmentUrl().contains("Signature"), presigned.attachmentUrl());
    assertFalse(presigned.attachmentUrl().contains("?"), presigned.attachmentUrl());
    assertEquals(Optional.of(key), adapter.parseUploadUrl(presigned.attachmentUrl()));
  }

  @Test
  void theBucketExpiresOrphanedTemporaryObjectsUnderTheUploadsPrefix() throws Exception {
    final String bucket = "bucket-" + UUID.randomUUID();
    presign(adapter(bucket), freshKey(), new byte[1_200_000]);

    final LifecycleConfiguration configuration =
        MinioAsyncClient.builder()
            .endpoint(AttachmentTestContainers.minioEndpoint())
            .credentials(
                AttachmentTestContainers.MINIO_USER, AttachmentTestContainers.MINIO_PASSWORD)
            .build()
            .getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucket).build())
            .get();

    assertEquals(1, configuration.rules().size());
    final LifecycleRule rule = configuration.rules().get(0);
    assertEquals(Status.ENABLED, rule.status());
    assertEquals("uploads/", rule.filter().prefix());
    assertEquals(1, rule.expiration().days());
  }

  @Test
  void parseUploadUrlAcceptsOnlyItsOwnEndpointAndBucket() {
    final String endpoint = AttachmentTestContainers.minioEndpoint();
    final MinioAttachmentStorageAdapter adapter = adapter("attachments");

    assertEquals(
        Optional.of("uploads/t-1/abc"),
        adapter.parseUploadUrl(endpoint + "/attachments/uploads/t-1/abc?X-Amz-Signature=s"));
    assertEquals(
        Optional.of("uploads/a%2Fb/abc"),
        adapter.parseUploadUrl(endpoint + "/attachments/uploads/a%252Fb/abc"));
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
    final MinioAttachmentStorageAdapter adapter = unreachable("bucket");

    StepVerifier.create(adapter.stat("uploads/t/x"))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify(Duration.ofSeconds(30));
    StepVerifier.create(adapter.presignUpload("uploads/t/x", PDF, 10L, Duration.ofMinutes(1)))
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
                new AttachmentProperties.ClamAv("localhost", 3310, 2)),
            Clock.systemUTC());

    StepVerifier.create(adapter.stat("uploads/t/x"))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();
    StepVerifier.create(adapter.presignUpload("uploads/t/x", PDF, 10L, Duration.ofMinutes(1)))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();
    assertEquals(
        Optional.of("uploads/t/x"),
        adapter.parseUploadUrl("http://localhost:9000/bucket/uploads/t/x"));
  }

  @Test
  void presignedUploadToStringHidesTheFields() {
    final String text =
        new PresignedUpload(
                "http://minio/b",
                Map.of("x-amz-signature", "secret-1"),
                "http://minio/b/uploads/t/x",
                Instant.now())
            .toString();

    assertFalse(text.contains("secret-1"), text);
  }

  @Test
  void pingIsTrueOnlyWhenTheStorageAnswers() {
    assertTrue(adapter("bucket-" + UUID.randomUUID()).ping().block());
    assertFalse(unreachable("bucket").ping().block());
  }

  private static MinioAsyncClient adminClient() {
    return MinioAsyncClient.builder()
        .endpoint(AttachmentTestContainers.minioEndpoint())
        .credentials(AttachmentTestContainers.MINIO_USER, AttachmentTestContainers.MINIO_PASSWORD)
        .build();
  }

  @Test
  void aPreexistingLifecycleRuleSurvivesAndTheOwnRuleIsKeptExactlyOnce() throws Exception {
    final String bucket = "bucket-" + UUID.randomUUID();
    final MinioAsyncClient admin = adminClient();
    admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()).get();
    admin
        .setBucketLifecycle(
            SetBucketLifecycleArgs.builder()
                .bucket(bucket)
                .config(
                    new LifecycleConfiguration(
                        List.of(
                            new LifecycleRule(
                                Status.ENABLED,
                                null,
                                new Expiration((ZonedDateTime) null, 7, null),
                                new RuleFilter("foreign/"),
                                "foreign-rule",
                                null,
                                null,
                                null))))
                .build())
        .get();

    presign(adapter(bucket), freshKey(), new byte[1_200_000]);
    presign(adapter(bucket), freshKey(), new byte[1_200_000]);

    final List<LifecycleRule> rules =
        admin
            .getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucket).build())
            .get()
            .rules();
    assertEquals(2, rules.size());
    final LifecycleRule foreign =
        rules.stream().filter(rule -> "foreign-rule".equals(rule.id())).findFirst().orElseThrow();
    assertEquals("foreign/", foreign.filter().prefix());
    assertEquals(7, foreign.expiration().days());
    final LifecycleRule own =
        rules.stream()
            .filter(rule -> "expire-orphaned-uploads".equals(rule.id()))
            .findFirst()
            .orElseThrow();
    assertEquals("uploads/", own.filter().prefix());
    assertEquals(1, own.expiration().days());
  }

  @Test
  void aFailedDeleteIsLoggedWithItsKeyAndASuccessfulOneIsNot() {
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    final Logger logger = (Logger) LoggerFactory.getLogger(MinioAttachmentStorageAdapter.class);
    logger.addAppender(logs);
    try {
      final String key = "uploads/tenant-1/" + UUID.randomUUID();

      final MinioAttachmentStorageAdapter reachable = adapter("bucket-" + UUID.randomUUID());
      presign(reachable, key, new byte[1_200_000]);
      reachable.delete(key).block();
      assertEquals(0, logs.list.size());

      StepVerifier.create(unreachable("bucket").delete(key))
          .expectError(AttachmentInspectionUnavailableException.class)
          .verify(Duration.ofSeconds(30));
      assertEquals(1, logs.list.size());
      final String line = LogLines.render(logs.list.get(0));
      assertTrue(line.contains("could not be deleted"), line);
      assertTrue(line.contains(key), line);
    } finally {
      logger.detachAppender(logs);
    }
  }
}
