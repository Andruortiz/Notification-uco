package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.infrastructure.support.AttachmentTestContainers;
import co.edu.uco.notification.infrastructure.support.RecordingAttachmentSender;
import co.edu.uco.notification.infrastructure.support.SampleFiles;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
      "notification.scheduler.requeue-interval-ms=1000"
    })
@Testcontainers
class NotificationAttachmentE2ETest {

  static final String TENANT = "tenant-attachments";
  static final String DOCS = "E2E_DOCS";
  static final String NO_ATTACHMENTS = "E2E_NO_ATTACHMENTS";
  static final String PLAIN_PROVIDER = "E2E_PLAIN_PROVIDER";
  static final String STRICT = "E2E_STRICT";
  static final String STRICT_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"attachments\":{\"type\":\"array\",\"maxItems\":2,"
          + "\"items\":{\"type\":\"object\",\"properties\":{\"contentType\":{\"enum\":[\"application/pdf\"]},"
          + "\"sizeBytes\":{\"maximum\":100000}}}}}}";
  static final String ALLOWED_TYPES =
      "[\"application/pdf\",\"image/png\",\"image/jpeg\",\"text/plain\",\"text/csv\","
          + "\"application/vnd.openxmlformats-officedocument.wordprocessingml.document\","
          + "\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\"]";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @DynamicPropertySource
  static void attachmentProperties(final DynamicPropertyRegistry registry) {
    AttachmentTestContainers.registerClamAv(registry);
  }

  @TestConfiguration
  static class RecordingSenders {

    @Bean
    RecordingAttachmentSender recordingAttachments() {
      return new RecordingAttachmentSender("recording-attachments", true);
    }

    @Bean
    RecordingAttachmentSender recordingPlain() {
      return new RecordingAttachmentSender("recording-plain", false);
    }
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired
  @Qualifier("recordingAttachments")
  private RecordingAttachmentSender recordingAttachments;

  @Autowired
  @Qualifier("recordingPlain")
  private RecordingAttachmentSender recordingPlain;

  private WebTestClient webTestClient;

  static String attachmentsSchema(final long maximumSize) {
    return "{\"type\":\"object\",\"properties\":{\"attachments\":{\"type\":\"array\",\"maxItems\":5,"
        + "\"items\":{\"type\":\"object\",\"properties\":{\"contentType\":{\"enum\":"
        + ALLOWED_TYPES
        + "},\"sizeBytes\":{\"maximum\":"
        + maximumSize
        + "}}}}}}";
  }

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    saveChannel(DOCS, "recording-attachments", attachmentsSchema(10_485_760L));
    saveChannel(NO_ATTACHMENTS, "recording-attachments", null);
    saveChannel(PLAIN_PROVIDER, "recording-plain", attachmentsSchema(10_485_760L));
    awaitSchema(DOCS, schema -> schema != null && schema.contains("10485760"));
    awaitSchema(NO_ATTACHMENTS, Objects::isNull);
    awaitSchema(PLAIN_PROVIDER, schema -> schema != null && schema.contains("10485760"));
    saveChannel(STRICT, "recording-attachments", STRICT_SCHEMA);
    awaitSchema(STRICT, schema -> schema != null && schema.contains("100000"));
  }

  void saveChannel(final String channel, final String provider, final String schema) {
    mongoTemplate.save(new ChannelCatalogDocument(channel, List.of(provider), schema)).block();
  }

  void awaitSchema(final String channel, final Predicate<String> expected) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort.findActiveRoute(ChannelType.of(channel), TenantId.of(TENANT)).block();
      if (route != null && expected.test(route.contentSchema())) {
        return;
      }
    }
    throw new IllegalStateException("channel " + channel + " did not refresh in time");
  }

  static Map<String, Object> embedded(
      final String fileName, final String type, final byte[] bytes) {
    final Map<String, Object> attachment = new HashMap<>();
    attachment.put("fileName", fileName);
    attachment.put("contentType", type);
    attachment.put("sizeBytes", bytes.length);
    attachment.put("content", Base64.getEncoder().encodeToString(bytes));
    return attachment;
  }

  static Map<String, Object> request(
      final String externalId, final String channel, final List<Map<String, Object>> attachments) {
    final Map<String, Object> body = new HashMap<>();
    body.put("externalId", externalId);
    body.put("channelType", channel);
    body.put("recipientId", "recipient-1");
    body.put("recipientAddress", "alice@example.com");
    body.put("subject", "Subject");
    body.put("body", "Body");
    body.put("priority", "NORMAL");
    if (attachments != null) {
      body.put("attachments", attachments);
    }
    return body;
  }

  WebTestClient.ResponseSpec post(final Object body) {
    return webTestClient
        .post()
        .uri("/notifications")
        .header("X-Tenant-Id", TENANT)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  Map<String, Object> accepted(final Object body) {
    final Map<String, Object> response =
        post(body)
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response;
  }

  String awaitStatus(final String notificationId, final String expected, final Duration timeout) {
    final Instant deadline = Instant.now().plus(timeout);
    String status = null;
    while (Instant.now().isBefore(deadline)) {
      final Map<String, Object> response =
          webTestClient
              .get()
              .uri("/notifications/{id}", notificationId)
              .header("X-Tenant-Id", TENANT)
              .exchange()
              .expectStatus()
              .isOk()
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

  Document storedNotification(final String notificationId) {
    return mongoTemplate
        .getCollection("notifications")
        .flatMap(
            collection -> Mono.from(collection.find(new Document("_id", notificationId)).first()))
        .block();
  }

  long storedNotificationsWithExternalId(final String externalId) {
    return mongoTemplate
        .getCollection("notifications")
        .flatMap(
            collection ->
                Mono.from(
                    collection.countDocuments(
                        new Document("tenantId", TENANT).append("externalId", externalId))))
        .block();
  }

  @Test
  void aCleanEmbeddedPdfIsAcceptedStoredWithItsHashAndDeliveredWithTheSameBytes() {
    final byte[] pdf = SampleFiles.pdfOfSize(500_000, 1);
    final byte[] png = SampleFiles.png();

    final String notificationId =
        accepted(
                request(
                    "us1-valid",
                    DOCS,
                    List.of(
                        embedded("invoice.pdf", "application/pdf", pdf),
                        embedded("receipt.png", "image/png", png))))
            .get("notificationId")
            .toString();

    assertEquals("DELIVERED", awaitStatus(notificationId, "DELIVERED", Duration.ofSeconds(20)));
    final Notification delivered =
        recordingAttachments.receivedFor(NotificationId.of(notificationId)).getFirst();
    final List<Attachment> attachments = delivered.content().attachments();
    assertEquals(
        List.of("invoice.pdf", "receipt.png"),
        attachments.stream().map(Attachment::fileName).toList());
    assertArrayEquals(
        pdf,
        assertInstanceOf(AttachmentSource.EmbeddedContent.class, attachments.get(0).source())
            .bytes());
    assertEquals(Sha256Digest.of(pdf), attachments.get(0).sha256());
    assertEquals(Sha256Digest.of(png), attachments.get(1).sha256());

    final List<Document> stored =
        storedNotification(notificationId).getList("attachments", Document.class);
    assertEquals("EMBEDDED", stored.get(0).getString("storage"));
    assertEquals(Sha256Digest.of(pdf).hex(), stored.get(0).getString("sha256"));
    assertArrayEquals(pdf, stored.get(0).get("content", Binary.class).getData());
  }

  @Test
  void aValidDuplicateReturnsTheOriginalWithoutChangingItsAttachments() {
    final byte[] first = SampleFiles.pdfOfSize(10_000, 2);
    final Map<String, Object> original =
        accepted(
            request("us1-duplicate", DOCS, List.of(embedded("a.pdf", "application/pdf", first))));

    final Map<String, Object> duplicate =
        accepted(
            request(
                "us1-duplicate",
                DOCS,
                List.of(embedded("b.pdf", "application/pdf", SampleFiles.pdfOfSize(20_000, 3)))));

    assertEquals(Boolean.TRUE, duplicate.get("duplicate"));
    assertEquals(original.get("notificationId"), duplicate.get("notificationId"));
    final List<Document> stored =
        storedNotification(original.get("notificationId").toString())
            .getList("attachments", Document.class);
    assertEquals(1, stored.size());
    assertEquals("a.pdf", stored.getFirst().getString("fileName"));
  }

  @Test
  void aNotificationWithoutAttachmentsBehavesAsBefore() {
    final String notificationId =
        accepted(request("us1-plain", NO_ATTACHMENTS, null)).get("notificationId").toString();

    assertEquals("DELIVERED", awaitStatus(notificationId, "DELIVERED", Duration.ofSeconds(20)));
    assertTrue(
        recordingAttachments
            .receivedFor(NotificationId.of(notificationId))
            .getFirst()
            .content()
            .attachments()
            .isEmpty());
  }

  @Test
  void fiveEmbeddedFilesOfOneMegabyteAreAcceptedWithinFiveSeconds() {
    accepted(
        request(
            "sc012-warmup",
            DOCS,
            List.of(embedded("warm.pdf", "application/pdf", SampleFiles.pdfOfSize(1_000, 99)))));
    final List<Map<String, Object>> attachments = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      attachments.add(
          embedded(
              "file-" + index + ".pdf",
              "application/pdf",
              SampleFiles.pdfOfSize(1_048_576, 1_000 + index + System.nanoTime())));
    }

    final Instant start = Instant.now();
    accepted(request("sc012", DOCS, attachments));
    final Duration elapsed = Duration.between(start, Instant.now());

    assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) <= 0, "accepted in " + elapsed);
  }

  @Test
  void aRetriedNotificationReceivesTheSameAttachmentsAgain() {
    final byte[] pdf = SampleFiles.pdfOfSize(4_000, 4);
    recordingAttachments.failFirstAttemptOf("us1-retry");

    final String notificationId =
        accepted(request("us1-retry", DOCS, List.of(embedded("retry.pdf", "application/pdf", pdf))))
            .get("notificationId")
            .toString();

    assertEquals("DELIVERED", awaitStatus(notificationId, "DELIVERED", Duration.ofSeconds(90)));
    final List<Notification> attempts =
        recordingAttachments.receivedFor(NotificationId.of(notificationId));
    assertEquals(2, attempts.size());
    assertEquals(attempts.get(0).content().attachments(), attempts.get(1).content().attachments());
    assertEquals(Sha256Digest.of(pdf), attempts.get(1).content().attachments().getFirst().sha256());
  }

  static Map<String, Object> attachment(
      final String fileName,
      final String type,
      final Object sizeBytes,
      final String content,
      final String url) {
    final Map<String, Object> attachment = new HashMap<>();
    attachment.put("fileName", fileName);
    attachment.put("contentType", type);
    attachment.put("sizeBytes", sizeBytes);
    attachment.put("content", content);
    attachment.put("url", url);
    return attachment;
  }

  static String base64(final byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  void assertRejected(
      final String externalId,
      final String channel,
      final List<Map<String, Object>> attachments,
      final String expectedFragment) {
    final Map<String, Object> error =
        post(request(externalId, channel, attachments))
            .expectStatus()
            .isBadRequest()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    final String message = String.valueOf(error.get("message"));
    assertTrue(message.contains(expectedFragment), externalId + " -> " + message);
    assertEquals(0L, storedNotificationsWithExternalId(externalId), externalId);
  }

  @Test
  void everyAttachmentRuleRejectsTheWholeNotificationAndStoresNothing() {
    final byte[] pdf = SampleFiles.pdf("rules");
    final String pdf64 = base64(pdf);
    final String upload = "http://localhost:9000/notification-attachments/tenants/x/uploads/y";
    final long tenMegabytes = 10_485_760L;
    final byte[] overOneMegabyte = SampleFiles.pdfOfSize(1_048_577, 7);

    assertRejected(
        "sc002-channel",
        NO_ATTACHMENTS,
        List.of(embedded("a.pdf", "application/pdf", pdf)),
        "channel does not accept attachments");
    assertRejected(
        "sc002-channel-type",
        STRICT,
        List.of(embedded("a.png", "image/png", SampleFiles.png())),
        "attachments[0]");
    assertRejected(
        "sc002-global-type",
        DOCS,
        List.of(embedded("a.gif", "image/gif", pdf)),
        "contentType image/gif is not allowed");
    assertRejected(
        "sc002-extension",
        DOCS,
        List.of(embedded("factura.pdf.exe", "application/pdf", pdf)),
        "fileName extension is not allowed");
    assertRejected(
        "sc002-channel-size",
        STRICT,
        List.of(embedded("big.pdf", "application/pdf", SampleFiles.pdfOfSize(200_000, 8))),
        "attachments[0]");
    assertRejected(
        "sc002-aggregate",
        DOCS,
        List.of(
            attachment("a.pdf", "application/pdf", tenMegabytes, null, upload),
            attachment("b.pdf", "application/pdf", tenMegabytes, null, upload),
            attachment("c.pdf", "application/pdf", tenMegabytes, null, upload)),
        "the total size of the attachments must not exceed 26214400 bytes");
    assertRejected(
        "sc002-global-count",
        DOCS,
        Collections.nCopies(6, embedded("a.pdf", "application/pdf", pdf)),
        "at most 5 attachments are allowed");
    assertRejected(
        "sc002-channel-count",
        STRICT,
        Collections.nCopies(3, embedded("a.pdf", "application/pdf", pdf)),
        "attachments");
    assertRejected(
        "sc002-name",
        DOCS,
        List.of(embedded("..", "application/pdf", pdf)),
        "attachments[0]: fileName");
    assertRejected(
        "sc002-missing-type",
        DOCS,
        List.of(attachment("a.pdf", null, pdf.length, pdf64, null)),
        "contentType is required");
    assertRejected(
        "sc002-size",
        DOCS,
        List.of(attachment("a.pdf", "application/pdf", 0, pdf64, null)),
        "sizeBytes must be at least 1");
    assertRejected(
        "sc002-both",
        DOCS,
        List.of(attachment("a.pdf", "application/pdf", pdf.length, pdf64, upload)),
        "exactly one of content or url is required");
    assertRejected(
        "sc002-neither",
        DOCS,
        List.of(attachment("a.pdf", "application/pdf", pdf.length, null, null)),
        "exactly one of content or url is required");
    assertRejected(
        "sc002-base64",
        DOCS,
        List.of(attachment("a.pdf", "application/pdf", 3, "not-base64", null)),
        "content must be valid Base64");
    assertRejected(
        "sc002-embedded-too-large",
        DOCS,
        List.of(embedded("big.pdf", "application/pdf", overOneMegabyte)),
        "content is only allowed for files of up to 1048576 bytes");
    assertRejected(
        "sc002-decoded-size",
        DOCS,
        List.of(attachment("a.pdf", "application/pdf", pdf.length + 1, pdf64, null)),
        "sizeBytes does not match the decoded content");
    assertRejected(
        "sc002-url-small",
        DOCS,
        List.of(attachment("a.pdf", "application/pdf", 1_000, null, upload)),
        "url is only allowed for files larger than 1048576 bytes");
    assertRejected(
        "sc002-real-type",
        DOCS,
        List.of(embedded("photo.pdf", "application/pdf", SampleFiles.png())),
        "the content is not application/pdf");
    assertRejected(
        "sc002-foreign-url",
        DOCS,
        List.of(
            attachment(
                "a.pdf", "application/pdf", 2_000_000, null, "https://files.example.test/a.pdf")),
        "url is not an upload issued by this service for this tenant");
  }

  @Test
  void theAntivirusTestFileIsRejectedAsMalwareAndNothingIsStored() {
    assertRejected(
        "sc008-embedded",
        DOCS,
        List.of(embedded("eicar.txt", "text/plain", SampleFiles.text(SampleFiles.EICAR))),
        "the file contains malicious software");
  }

  @Test
  void aBodyOverEightMegabytesIsRejectedAsTooLarge() {
    final List<Map<String, Object>> attachments = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      attachments.add(
          attachment(
              "f" + index + ".pdf",
              "application/pdf",
              1_048_576,
              base64(SampleFiles.pdfOfSize(1_300_000, 50 + index)),
              null));
    }

    post(request("sc002-413", DOCS, attachments)).expectStatus().isEqualTo(413);
    assertEquals(0L, storedNotificationsWithExternalId("sc002-413"));
  }

  @Autowired private AmqpAdmin amqpAdmin;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  @Test
  void theEmbeddedContentNeverLeavesTheServiceWhileItsMetadataIsLogged() {
    final String marker = "LEAK-MARKER-7761-" + System.nanoTime();
    final byte[] content = SampleFiles.text("confidential " + marker + "\n");
    final String encoded = base64(content);
    final Queue events = new Queue("sc003-events-" + System.nanoTime(), false, false, false);
    amqpAdmin.declareQueue(events);
    amqpAdmin.declareBinding(
        new Binding(
            events.getName(),
            Binding.DestinationType.QUEUE,
            rabbitTopologyProperties.eventsExchange(),
            "",
            null));
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    try {
      final String notificationId =
          accepted(
                  request(
                      "sc003-valid", DOCS, List.of(embedded("secret.txt", "text/plain", content))))
              .get("notificationId")
              .toString();
      assertEquals("DELIVERED", awaitStatus(notificationId, "DELIVERED", Duration.ofSeconds(20)));
      final String errorBody =
          post(request(
                  "sc003-invalid",
                  DOCS,
                  List.of(embedded("secret.pdf", "application/pdf", content))))
              .expectStatus()
              .isBadRequest()
              .expectBody(String.class)
              .returnResult()
              .getResponseBody();
      final String statusBody = getBody("/notifications/" + notificationId);
      final String searchBody = getBody("/notifications?limit=50");
      final List<String> eventBodies = new ArrayList<>();
      Message message = rabbitTemplate.receive(events.getName(), 2_000);
      while (message != null) {
        eventBodies.add(new String(message.getBody(), StandardCharsets.UTF_8));
        message = rabbitTemplate.receive(events.getName(), 500);
      }
      final List<String> logLines =
          logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

      final List<String> everything = new ArrayList<>(logLines);
      everything.add(errorBody);
      everything.add(statusBody);
      everything.add(searchBody);
      everything.addAll(eventBodies);
      assertTrue(everything.stream().noneMatch(text -> text.contains(marker)), "marker leaked");
      assertTrue(everything.stream().noneMatch(text -> text.contains(encoded)), "content leaked");
      assertTrue(!eventBodies.isEmpty(), "events were captured");
      assertTrue(
          logLines.stream()
              .anyMatch(
                  line ->
                      line.contains(
                          "secret.txt|text/plain|"
                              + content.length
                              + "|"
                              + Sha256Digest.of(content).hex())),
          "metadata must be logged");
      assertTrue(
          logLines.stream()
              .anyMatch(
                  line ->
                      line.startsWith("Notification with attachments rejected")
                          && line.contains("secret.pdf|application/pdf|" + content.length)),
          "rejection must be logged");
    } finally {
      ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
      amqpAdmin.deleteQueue(events.getName());
    }
  }

  private String getBody(final String uri) {
    return webTestClient
        .get()
        .uri(uri)
        .header("X-Tenant-Id", TENANT)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  @Test
  void theCatalogShowsTheChannelDeclarationAndTheGlobalCapAlwaysWins() {
    final String catalog = getBody("/channels");
    assertTrue(catalog.contains(DOCS), catalog);
    assertTrue(catalog.contains("\\\"attachments\\\""), catalog);

    saveChannel("E2E_GENEROUS", "recording-attachments", attachmentsSchema(20_000_000L));
    awaitSchema("E2E_GENEROUS", schema -> schema != null && schema.contains("20000000"));
    assertRejected(
        "us3-global-cap",
        "E2E_GENEROUS",
        List.of(
            attachment(
                "big.pdf",
                "application/pdf",
                10_485_761L,
                null,
                "http://localhost:9000/notification-attachments/tenants/x/uploads/y")),
        "sizeBytes must not exceed 10485760");
  }

  @Test
  void theDefaultEmailChannelDoesNotAcceptAttachments() {
    assertRejected(
        "us3-default-email",
        "EMAIL",
        List.of(embedded("a.pdf", "application/pdf", SampleFiles.pdf("email"))),
        "channel does not accept attachments");
  }

  @Test
  void aSmallerChannelMaximumIsAppliedWithinFiveSeconds() {
    saveChannel("E2E_SHRINK", "recording-attachments", attachmentsSchema(10_485_760L));
    awaitSchema("E2E_SHRINK", schema -> schema != null && schema.contains("10485760"));
    final byte[] pdf = SampleFiles.pdfOfSize(200_000, 9);
    accepted(
        request("sc005-before", "E2E_SHRINK", List.of(embedded("a.pdf", "application/pdf", pdf))));

    saveChannel("E2E_SHRINK", "recording-attachments", attachmentsSchema(100_000L));
    final Instant changedAt = Instant.now();
    int attempt = 0;
    Duration elapsed = Duration.ZERO;
    while (elapsed.compareTo(Duration.ofSeconds(10)) < 0) {
      final int status =
          post(request(
                  "sc005-after-" + attempt++,
                  "E2E_SHRINK",
                  List.of(embedded("a.pdf", "application/pdf", pdf))))
              .returnResult(String.class)
              .getStatus()
              .value();
      elapsed = Duration.between(changedAt, Instant.now());
      if (status == 400) {
        break;
      }
    }

    assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0, "applied after " + elapsed);
  }

  @Test
  void aProviderWithoutAttachmentSupportNeverDeliversANotificationWithAttachments() {
    final String withAttachment =
        accepted(
                request(
                    "sc006-with",
                    PLAIN_PROVIDER,
                    List.of(embedded("a.pdf", "application/pdf", SampleFiles.pdf("sc006")))))
            .get("notificationId")
            .toString();
    final String withoutAttachment =
        accepted(request("sc006-without", PLAIN_PROVIDER, null)).get("notificationId").toString();

    assertEquals("FAILED", awaitStatus(withAttachment, "FAILED", Duration.ofSeconds(20)));
    assertEquals("DELIVERED", awaitStatus(withoutAttachment, "DELIVERED", Duration.ofSeconds(20)));
    assertTrue(recordingPlain.receivedFor(NotificationId.of(withAttachment)).isEmpty());
    assertEquals(1, recordingPlain.receivedFor(NotificationId.of(withoutAttachment)).size());
  }
}
