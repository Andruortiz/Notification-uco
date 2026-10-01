package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSource;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.AttachmentStoragePort;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.infrastructure.support.AttachmentTestContainers;
import co.edu.uco.notification.infrastructure.support.RecordingAttachmentSender;
import co.edu.uco.notification.infrastructure.support.SampleFiles;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=1000",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Testcontainers
class AttachmentUploadE2ETest {

  private static final String TENANT_A = "tenant-uploads-a";
  private static final String TENANT_B = "tenant-uploads-b";
  private static final String CHANNEL = "E2E_UPLOADS";
  private static final String PDF = "application/pdf";
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @DynamicPropertySource
  static void attachmentProperties(final DynamicPropertyRegistry registry) {
    AttachmentTestContainers.registerClamAv(registry);
    AttachmentTestContainers.registerMinio(registry);
  }

  @TestConfiguration
  static class RecordingSenders {

    @Bean
    RecordingAttachmentSender uploadsRecorder() {
      return new RecordingAttachmentSender("uploads-recorder", true);
    }
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private RecordingAttachmentSender uploadsRecorder;

  @Autowired private AttachmentStoragePort storage;

  @Autowired private AmqpAdmin amqpAdmin;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  private WebTestClient webTestClient;

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
    mongoTemplate
        .save(
            new ChannelCatalogDocument(
                CHANNEL,
                List.of("uploads-recorder"),
                NotificationAttachmentE2ETest.attachmentsSchema(10_485_760L)))
        .block();
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort
              .findActiveRoute(ChannelType.of(CHANNEL), TenantId.of(TENANT_A))
              .block();
      if (route != null && route.contentSchema() != null) {
        break;
      }
    }
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
  }

  private Map<String, Object> issue(
      final String tenant, final String fileName, final String type, final long size) {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/attachment-uploads")
            .header("Authorization", TestTokens.bearer(tenant))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("fileName", fileName, "contentType", type, "sizeBytes", size))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response;
  }

  private static int put(final Object url, final byte[] content)
      throws IOException, InterruptedException {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url.toString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private WebTestClient.ResponseSpec complete(final String tenant, final Object uploadId) {
    return webTestClient
        .post()
        .uri("/attachment-uploads/" + uploadId + ":complete")
        .header("Authorization", TestTokens.bearer(tenant))
        .exchange();
  }

  private WebTestClient.ResponseSpec getUpload(final String tenant, final Object uploadId) {
    return webTestClient
        .get()
        .uri("/attachment-uploads/" + uploadId)
        .header("Authorization", TestTokens.bearer(tenant))
        .exchange();
  }

  private Map<String, Object> awaitState(
      final String tenant, final Object uploadId, final String expected, final Duration timeout) {
    final Instant deadline = Instant.now().plus(timeout);
    Map<String, Object> upload = Map.of();
    while (Instant.now().isBefore(deadline)) {
      upload =
          getUpload(tenant, uploadId)
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();
      if (expected.equals(upload.get("state"))) {
        return upload;
      }
      Mono.delay(Duration.ofMillis(200)).block();
    }
    return upload;
  }

  private Map<String, Object> uploadedAndScanned(
      final String tenant, final String fileName, final String type, final byte[] content)
      throws IOException, InterruptedException {
    final Map<String, Object> issued = issue(tenant, fileName, type, content.length);
    assertEquals(200, put(issued.get("uploadUrl"), content));
    complete(tenant, issued.get("uploadId")).expectStatus().isAccepted();
    return issued;
  }

  private static Map<String, Object> notificationWith(
      final String externalId,
      final String fileName,
      final String type,
      final long size,
      final Object url) {
    final Map<String, Object> attachment = new HashMap<>();
    attachment.put("fileName", fileName);
    attachment.put("contentType", type);
    attachment.put("sizeBytes", size);
    attachment.put("url", url);
    final Map<String, Object> body = new HashMap<>();
    body.put("externalId", externalId);
    body.put("channelType", CHANNEL);
    body.put("recipientId", "recipient-1");
    body.put("recipientAddress", "alice@example.com");
    body.put("body", "Body");
    body.put("priority", "NORMAL");
    body.put("attachments", List.of(attachment));
    return body;
  }

  private WebTestClient.ResponseSpec send(final String tenant, final Map<String, Object> body) {
    return webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer(tenant))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private String bodyOf(final WebTestClient.ResponseSpec spec, final int status) {
    return spec.expectStatus()
        .isEqualTo(status)
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private String awaitNotificationStatus(
      final String tenant, final String id, final String expected) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    String status = null;
    while (Instant.now().isBefore(deadline)) {
      final Map<String, Object> response =
          webTestClient
              .get()
              .uri("/notifications/{id}", id)
              .header("Authorization", TestTokens.bearer(tenant))
              .exchange()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();
      status = String.valueOf(response.get("status"));
      if (expected.equals(status)) {
        return status;
      }
      Mono.delay(Duration.ofMillis(200)).block();
    }
    return status;
  }

  private List<String> logLines() {
    return logs.list.stream().map(LogLines::render).toList();
  }

  private static String signatureOf(final Object uploadUrl) {
    final String url = uploadUrl.toString();
    return url.substring(url.indexOf("X-Amz-Signature=") + "X-Amz-Signature=".length());
  }

  @Test
  void aCleanTenMegabyteFileIsScannedInTimeAndDeliveredByReference() throws Exception {
    final byte[] content = SampleFiles.pdfOfSize(10_485_760, 42);
    final Map<String, Object> issued = issue(TENANT_A, "contract.pdf", PDF, content.length);
    assertEquals("PENDING_SCAN", issued.get("state"));
    assertEquals(200, put(issued.get("uploadUrl"), content));

    final Instant completedAt = Instant.now();
    complete(TENANT_A, issued.get("uploadId")).expectStatus().isAccepted();
    final Map<String, Object> clean =
        awaitState(TENANT_A, issued.get("uploadId"), "CLEAN", Duration.ofSeconds(60));
    final Duration scanTime = Duration.between(completedAt, Instant.now());

    assertEquals("CLEAN", clean.get("state"));
    assertTrue(scanTime.compareTo(Duration.ofSeconds(30)) <= 0, "scanned in " + scanTime);
    assertEquals(Sha256Digest.of(content).hex(), clean.get("sha256"));

    final String notificationId =
        send(
                TENANT_A,
                notificationWith(
                    "sc001-large", "contract.pdf", PDF, content.length, issued.get("uploadUrl")))
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody()
            .get("notificationId")
            .toString();
    assertEquals("DELIVERED", awaitNotificationStatus(TENANT_A, notificationId, "DELIVERED"));

    final Notification delivered =
        uploadsRecorder.receivedFor(NotificationId.of(notificationId)).getFirst();
    final Attachment attachment = delivered.content().attachments().getFirst();
    assertEquals(Sha256Digest.of(content), attachment.sha256());
    final AttachmentSource.StoredObject stored =
        assertInstanceOf(AttachmentSource.StoredObject.class, attachment.source());
    assertEquals((long) content.length, storage.stat(stored.objectKey()).block().sizeBytes());
    assertNull(storage.stat("tenants/" + TENANT_A + "/uploads/" + issued.get("uploadId")).block());
    final Document document =
        mongoTemplate
            .getCollection("notifications")
            .flatMap(c -> Mono.from(c.find(new Document("_id", notificationId)).first()))
            .block();
    assertFalse(document.toJson().contains("X-Amz-Signature"), document.toJson());
  }

  @Test
  void anUploadStillPendingIsAConflictAndAForeignUrlIsInvalid() {
    final Map<String, Object> issued = issue(TENANT_A, "pending.pdf", PDF, 2_000_000L);

    bodyOf(
        send(
            TENANT_A,
            notificationWith(
                "sc009-pending", "pending.pdf", PDF, 2_000_000L, issued.get("uploadUrl"))),
        409);
    final String foreign =
        bodyOf(
            send(
                TENANT_A,
                notificationWith(
                    "sc009-foreign",
                    "pending.pdf",
                    PDF,
                    2_000_000L,
                    "https://files.example.test/pending.pdf")),
            400);
    assertTrue(foreign.contains("url is not an upload issued"), foreign);
  }

  @Test
  void anInfectedUploadIsRejectedByTheScanAndCannotBeReferenced() throws Exception {
    final byte[] infected = SampleFiles.docxWithEicar(1_200_000);
    final Map<String, Object> issued = uploadedAndScanned(TENANT_A, "virus.docx", DOCX, infected);

    final Map<String, Object> result =
        awaitState(TENANT_A, issued.get("uploadId"), "INFECTED", Duration.ofSeconds(60));

    assertEquals("INFECTED", result.get("state"));
    assertEquals("MALWARE", result.get("rejectionReason"));
    assertNull(storage.stat("tenants/" + TENANT_A + "/uploads/" + issued.get("uploadId")).block());
    final String rejected =
        bodyOf(
            send(
                TENANT_A,
                notificationWith(
                    "sc008-large", "virus.docx", DOCX, infected.length, issued.get("uploadUrl"))),
            400);
    assertTrue(rejected.contains("the upload was rejected by the scan"), rejected);
    assertTrue(
        logLines().stream()
            .anyMatch(line -> line.contains("state=INFECTED") && line.contains("Eicar")),
        "the verdict and its signature must be logged");
  }

  @Test
  void completingWithoutTheFileOrWithAnotherSizeKeepsTheUploadPending() throws Exception {
    final Map<String, Object> issued = issue(TENANT_A, "late.pdf", PDF, 2_000_000L);

    bodyOf(complete(TENANT_A, issued.get("uploadId")), 409);
    assertEquals(200, put(issued.get("uploadUrl"), SampleFiles.pdfOfSize(1_500_000, 5)));
    final String wrongSize = bodyOf(complete(TENANT_A, issued.get("uploadId")), 400);

    assertTrue(wrongSize.contains("size does not match"), wrongSize);
    assertEquals(
        "PENDING_SCAN",
        awaitState(TENANT_A, issued.get("uploadId"), "PENDING_SCAN", Duration.ofSeconds(5))
            .get("state"));
    assertEquals(200, put(issued.get("uploadUrl"), SampleFiles.pdfOfSize(2_000_000, 6)));
    complete(TENANT_A, issued.get("uploadId")).expectStatus().isAccepted();
    assertEquals(
        "CLEAN",
        awaitState(TENANT_A, issued.get("uploadId"), "CLEAN", Duration.ofSeconds(60)).get("state"));
  }

  @Test
  void theSignedUploadUrlNeverLeavesTheServiceAfterItsIssuance() throws Exception {
    final Queue events = new Queue("upload-events-" + System.nanoTime(), false, false, false);
    amqpAdmin.declareQueue(events);
    amqpAdmin.declareBinding(
        new Binding(
            events.getName(),
            Binding.DestinationType.QUEUE,
            rabbitTopologyProperties.eventsExchange(),
            "",
            null));
    try {
      final byte[] content = SampleFiles.pdfOfSize(1_500_000, 77);
      final Map<String, Object> issued =
          uploadedAndScanned(TENANT_A, "leak-check.pdf", PDF, content);
      final String signature = signatureOf(issued.get("uploadUrl"));
      final Map<String, Object> clean =
          awaitState(TENANT_A, issued.get("uploadId"), "CLEAN", Duration.ofSeconds(60));
      final String notificationId =
          send(
                  TENANT_A,
                  notificationWith(
                      "sc003-large",
                      "leak-check.pdf",
                      PDF,
                      content.length,
                      issued.get("uploadUrl")))
              .expectStatus()
              .isAccepted()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody()
              .get("notificationId")
              .toString();
      awaitNotificationStatus(TENANT_A, notificationId, "DELIVERED");
      final String rejected =
          bodyOf(
              send(
                  TENANT_A,
                  notificationWith(
                      "sc003-large-bad",
                      "other.pdf",
                      PDF,
                      content.length,
                      issued.get("uploadUrl"))),
              400);

      final List<String> everything = new ArrayList<>(logLines());
      everything.add(rejected);
      everything.add(bodyOf(getUpload(TENANT_A, issued.get("uploadId")), 200));
      everything.add(
          bodyOf(
              webTestClient
                  .get()
                  .uri("/notifications/" + notificationId)
                  .header("Authorization", TestTokens.bearer(TENANT_A))
                  .exchange(),
              200));
      everything.add(
          bodyOf(
              webTestClient
                  .get()
                  .uri("/notifications?limit=50")
                  .header("Authorization", TestTokens.bearer(TENANT_A))
                  .exchange(),
              200));
      Message message = rabbitTemplate.receive(events.getName(), 2_000);
      while (message != null) {
        everything.add(new String(message.getBody(), StandardCharsets.UTF_8));
        message = rabbitTemplate.receive(events.getName(), 500);
      }

      assertTrue(
          everything.stream().noneMatch(text -> text.contains(signature)), "signature leaked");
      assertTrue(
          logLines().stream()
              .anyMatch(
                  line ->
                      line.contains(
                          "leak-check.pdf|application/pdf|"
                              + content.length
                              + "|"
                              + clean.get("sha256"))),
          "metadata and hash must be logged");
    } finally {
      amqpAdmin.deleteQueue(events.getName());
    }
  }

  @Test
  void anotherTenantCanNeitherSeeNorCompleteNorReferenceAnUploadNorReuseItsVerdict()
      throws Exception {
    final byte[] content = SampleFiles.pdfOfSize(1_600_000, 1234);
    final Map<String, Object> issued =
        uploadedAndScanned(TENANT_A, "tenant-a-private-contract.pdf", PDF, content);
    final Object uploadId = issued.get("uploadId");
    final Map<String, Object> clean =
        awaitState(TENANT_A, uploadId, "CLEAN", Duration.ofSeconds(60));
    final String sha = clean.get("sha256").toString();
    logs.list.clear();

    final String getAsB = bodyOf(getUpload(TENANT_B, uploadId), 404);
    final String getUnknown = bodyOf(getUpload(TENANT_B, "never-issued"), 404);
    final String completeAsB = bodyOf(complete(TENANT_B, uploadId), 404);
    final String completeUnknown = bodyOf(complete(TENANT_B, "never-issued"), 404);
    final String referenceAsB =
        bodyOf(
            send(
                TENANT_B,
                notificationWith("us7-b", "b.pdf", PDF, content.length, issued.get("uploadUrl"))),
            400);
    final String unknownReference =
        bodyOf(
            send(
                TENANT_B,
                notificationWith(
                    "us7-b-unknown",
                    "b.pdf",
                    PDF,
                    content.length,
                    issued
                        .get("uploadUrl")
                        .toString()
                        .replace(uploadId.toString(), "never-issued"))),
            400);

    assertEquals(getUnknown, getAsB);
    assertEquals(completeUnknown, completeAsB);
    assertEquals(unknownReference, referenceAsB);
    final List<String> seenByB = new ArrayList<>(logLines());
    seenByB.addAll(List.of(getAsB, completeAsB, referenceAsB));
    for (final String leak :
        List.of(uploadId.toString(), sha, "tenant-a-private-contract.pdf", TENANT_A)) {
      assertTrue(seenByB.stream().noneMatch(text -> text.contains(leak)), "leaked " + leak);
    }
    assertEquals(
        0L,
        mongoTemplate
            .getCollection("notifications")
            .flatMap(c -> Mono.from(c.countDocuments(new Document("tenantId", TENANT_B))))
            .block());

    final Map<String, Object> issuedByB = uploadedAndScanned(TENANT_B, "b-copy.pdf", PDF, content);
    assertEquals(
        sha,
        awaitState(TENANT_B, issuedByB.get("uploadId"), "CLEAN", Duration.ofSeconds(60))
            .get("sha256"));
    assertEquals(
        2L,
        mongoTemplate
            .getCollection("attachment_scan_verdicts")
            .flatMap(c -> Mono.from(c.countDocuments(new Document("sha256", sha))))
            .block());

    send(
            TENANT_A,
            notificationWith(
                "us7-a",
                "tenant-a-private-contract.pdf",
                PDF,
                content.length,
                issued.get("uploadUrl")))
        .expectStatus()
        .isAccepted();
  }
}
