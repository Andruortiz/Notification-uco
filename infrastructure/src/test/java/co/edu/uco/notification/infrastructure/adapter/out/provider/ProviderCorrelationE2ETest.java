package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import co.edu.uco.notification.utils.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.provider.brevo.api-key=e2e-correlation-key",
      "notification.provider.brevo.sender-email=sender@example.com",
      "notification.provider.twilio.account-sid=" + ProviderCorrelationE2ETest.ACCOUNT_SID,
      "notification.provider.twilio.auth-token=e2e-correlation-token",
      "notification.provider.twilio.from-number=+15005550006"
    })
@Testcontainers
class ProviderCorrelationE2ETest {

  static final String ACCOUNT_SID = "AC" + "00112233445566778899aabbccddeeff";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FcmTestCredentials CREDENTIALS = FcmTestCredentials.shared();
  private static final String TENANT = "tenant-1";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer fakeBrevo;
  static FakeProviderServer fakeTwilio;
  static FakeProviderServer fakeFcm;
  static FakeProviderServer fakeAuthorization;

  @DynamicPropertySource
  static void providerEndpoints(final DynamicPropertyRegistry registry) {
    fakeBrevo = FakeProviderServer.start();
    fakeTwilio = FakeProviderServer.start();
    fakeFcm = FakeProviderServer.start();
    fakeAuthorization = FakeProviderServer.start();
    registry.add("notification.provider.brevo.base-url", () -> fakeBrevo.baseUrl());
    registry.add("notification.provider.twilio.base-url", () -> fakeTwilio.baseUrl());
    registry.add("notification.provider.fcm.credentials-json", CREDENTIALS::json);
    registry.add("notification.provider.fcm.base-url", () -> fakeFcm.baseUrl());
    registry.add(
        "notification.provider.fcm.token-url", () -> fakeAuthorization.baseUrl() + "/token");
  }

  @AfterAll
  static void stopFakeServers() {
    fakeBrevo.stop();
    fakeTwilio.stop();
    fakeFcm.stop();
    fakeAuthorization.stop();
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    fakeBrevo.reset();
    fakeTwilio.reset();
    fakeFcm.reset();
    fakeAuthorization.reset();
    fakeAuthorization.nextResponse(200, "{\"access_token\":\"e2e-access\",\"expires_in\":3600}");
    fakeFcm.nextResponse(200, "{\"name\":\"projects/demo-project/messages/0:msg-1\"}");
    fakeTwilio.nextResponse(201, "{\"sid\":\"SM00112233445566778899aabbccddeeff\"}");
    fakeBrevo.nextResponse(201, "{\"messageId\":\"<e2e@smtp-relay.mailin.fr>\"}");
  }

  private void preferProvider(final String channel, final String provider) {
    final ChannelCatalogDocument seeded =
        mongoTemplate.findById(channel, ChannelCatalogDocument.class).block();
    assertNotNull(seeded, "el catalogo sembrado debe declarar el canal " + channel);
    mongoTemplate
        .save(new ChannelCatalogDocument(channel, List.of(provider), seeded.contentSchema()))
        .block();
    final ProviderId expected = ProviderId.of(provider);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final var route =
          channelCatalogPort.findActiveRoute(ChannelType.of(channel), TenantId.of(TENANT)).block();
      if (route != null && route.preferredProvider().equals(expected)) {
        return;
      }
      pause();
    }
    throw new IllegalStateException("El catalogo nunca prefirio " + provider);
  }

  private static void pause() {
    try {
      Thread.sleep(100);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private String accept(
      final String channel, final String address, final String subject, final String correlation) {
    final Map<String, Object> request = new HashMap<>();
    request.put("externalId", channel.toLowerCase() + "-corr-" + System.nanoTime());
    request.put("channelType", channel);
    request.put("recipientId", "recipient-1");
    request.put("recipientAddress", address);
    request.put("body", "Contenido de prueba");
    request.put("priority", "NORMAL");
    request.put("subject", subject);
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(TENANT))
            .header(CorrelationId.HEADER, correlation)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response.get("notificationId").toString();
  }

  private void awaitDelivered(final String notificationId) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    String current = "";
    while (Instant.now().isBefore(deadline) && !"DELIVERED".equals(current)) {
      final Map<String, Object> view =
          webTestClient
              .get()
              .uri("/notifications/{id}", notificationId)
              .header("Authorization", TestTokens.bearer(TENANT))
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();
      current = String.valueOf(view == null ? null : view.get("status"));
      pause();
    }
    assertEquals("DELIVERED", current);
  }

  private static void assertNoCorrelationIn(
      final FakeProviderServer server, final String correlation) {
    assertFalse(server.requests().isEmpty(), "el proveedor debia recibir la solicitud");
    server
        .requests()
        .forEach(
            request -> {
              assertFalse(request.path().contains(correlation));
              assertFalse(request.body().contains(correlation));
              request
                  .headers()
                  .forEach(
                      (name, values) -> {
                        assertFalse(name.equalsIgnoreCase(CorrelationId.HEADER), name);
                        assertFalse(values.toString().contains(correlation), name);
                      });
            });
  }

  @Test
  void brevoReceivesTheRequestCorrelationIdAsTheOnlyTag() throws Exception {
    preferProvider("EMAIL", "brevo");
    final String correlation = "corr-e2e-brevo-001";

    awaitDelivered(accept("EMAIL", "alice@example.com", "Hola", correlation));

    assertEquals(1, fakeBrevo.requests().size());
    final JsonNode tags = MAPPER.readTree(fakeBrevo.requests().get(0).body()).get("tags");
    assertEquals(1, tags.size());
    assertEquals(correlation, tags.get(0).asText());
    assertTrue(fakeTwilio.requests().isEmpty());
    assertTrue(fakeFcm.requests().isEmpty());
  }

  @Test
  void twilioReceivesNoCorrelationId() {
    preferProvider("SMS", "twilio");
    final String correlation = "corr-e2e-twilio-001";

    awaitDelivered(accept("SMS", "+573001234567", "Hola", correlation));

    assertEquals(1, fakeTwilio.requests().size());
    assertNoCorrelationIn(fakeTwilio, correlation);
    assertTrue(fakeBrevo.requests().isEmpty());
  }

  @Test
  void fcmReceivesNoCorrelationId() {
    preferProvider("PUSH", "fcm");
    final String correlation = "corr-e2e-fcm-001";

    awaitDelivered(accept("PUSH", "device-" + "token-e2e-5678", "Hola", correlation));

    assertEquals(1, fakeFcm.requests().size());
    assertNoCorrelationIn(fakeFcm, correlation);
    assertTrue(fakeBrevo.requests().isEmpty());
  }
}
