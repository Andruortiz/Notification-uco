package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.support.AttachmentTestContainers;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
abstract class ApiV2E2ESupport {

  protected static final String TENANT_A = "tenant-v2-a";
  protected static final String TENANT_B = "tenant-v2-b";

  @ServiceConnection static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static {
    MONGO.start();
    RABBIT.start();
  }

  @DynamicPropertySource
  static void attachmentProperties(final DynamicPropertyRegistry registry) {
    AttachmentTestContainers.registerClamAv(registry);
    AttachmentTestContainers.registerMinio(registry);
  }

  @LocalServerPort protected int port;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  protected WebTestClient client;

  @BeforeEach
  void connect() {
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)
        && channelCatalogPort
            .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("readiness-check"))
            .blockOptional()
            .isEmpty()) {
      Thread.onSpinWait();
    }
  }

  protected static Map<String, Object> notification(
      final String externalId, final String recipientId) {
    return Map.of(
        "externalId", externalId,
        "channelType", "EMAIL",
        "recipientId", recipientId,
        "recipientAddress", "alice@example.com",
        "subject", "Subject",
        "body", "Body",
        "priority", "NORMAL");
  }

  protected Map<String, Object> send(final String tenant, final Map<String, Object> body) {
    final Map<String, Object> response =
        client
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(tenant))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response;
  }

  protected WebTestClient.ResponseSpec detail(final String tenant, final Object notificationId) {
    return client
        .get()
        .uri("/notifications/detail?notificationId={id}", notificationId)
        .header("Authorization", TestTokens.bearer(tenant))
        .exchange();
  }

  protected WebTestClient.ResponseSpec issueUpload(final String tenant) {
    return client
        .post()
        .uri("/attachment-uploads")
        .header("Authorization", TestTokens.bearer(tenant))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "fileName", "contrato.txt",
                "contentType", "text/plain",
                "sizeBytes", 1_048_577L))
        .exchange();
  }

  @SuppressWarnings("unchecked")
  protected static int uploadFile(final Map<String, Object> issued, final byte[] content)
      throws IOException, InterruptedException {
    final String boundary = "----form" + System.nanoTime();
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (final Map.Entry<String, String> field :
        ((Map<String, String>) issued.get("uploadFields")).entrySet()) {
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
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.writeBytes(content);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(issued.get("uploadUrl").toString()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  protected static byte[] fileOfBytes(final int size) {
    final byte[] content = new byte[size];
    java.util.Arrays.fill(content, (byte) 'a');
    return content;
  }

  protected static void assertNotServed(final int status) {
    assertTrue(status == 404 || status == 405, "expected 404 or 405 but was " + status);
  }
}
