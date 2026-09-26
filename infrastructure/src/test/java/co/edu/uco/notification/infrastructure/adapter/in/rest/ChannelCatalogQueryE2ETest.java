package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.exception.ProviderNotAvailableException;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=1000",
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.provider.brevo.api-key=",
      "notification.provider.twilio.account-sid=",
      "notification.provider.twilio.auth-token=" + ChannelCatalogQueryE2ETest.TWILIO_AUTH_TOKEN,
      "notification.provider.fcm.credentials-json=",
      "notification.provider.fcm.credentials-file="
    })
@Testcontainers
class ChannelCatalogQueryE2ETest {

  static final String TWILIO_AUTH_TOKEN = "hu2085-" + "twilio-token-" + "reconocible";

  private static final String TENANT = "tenant-catalogo-a";
  private static final String OTHER_TENANT = "tenant-catalogo-b";
  private static final Duration CATALOG_WAIT = Duration.ofSeconds(20);
  private static final Duration SC005_THRESHOLD = Duration.ofSeconds(5);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogProperties catalogProperties;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private NotificationSenderRegistry notificationSenderRegistry;

  private WebTestClient webTestClient;

  @BeforeEach
  void restoreTheConfiguredCatalog() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    mongoTemplate.remove(new Query(), ChannelCatalogDocument.class).block();
    mongoTemplate.insertAll(configuredDocuments()).collectList().block();
    awaitChannels(this::matchesTheConfiguredCatalog);
  }

  private List<ChannelCatalogDocument> configuredDocuments() {
    final List<ChannelCatalogDocument> documents = new ArrayList<>();
    catalogProperties
        .channels()
        .forEach(
            (channel, entry) ->
                documents.add(
                    new ChannelCatalogDocument(channel, entry.providers(), entry.contentSchema())));
    return documents;
  }

  private boolean matchesTheConfiguredCatalog(final JsonNode channels) {
    final Map<String, ChannelCatalogProperties.ChannelEntry> configured =
        catalogProperties.channels();
    if (channels.get("items").size() != configured.size()) {
      return false;
    }
    for (final JsonNode channel : channels.get("items")) {
      final ChannelCatalogProperties.ChannelEntry entry =
          configured.get(channel.get("channelType").asText());
      if (entry == null || !entry.providers().equals(providerIdsOf(channel))) {
        return false;
      }
    }
    return true;
  }

  private static List<String> providerIdsOf(final JsonNode channel) {
    return StreamSupport.stream(channel.get("providers").spliterator(), false)
        .map(provider -> provider.get("providerId").asText())
        .toList();
  }

  private String getBody(final String path, final String tenant) {
    return webTestClient
        .get()
        .uri(path)
        .header("X-Tenant-Id", tenant)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private static JsonNode parse(final String body) {
    try {
      return MAPPER.readTree(body);
    } catch (final Exception e) {
      throw new IllegalStateException("La respuesta no es JSON valido: " + body, e);
    }
  }

  private JsonNode channels() {
    return parse(getBody("/channels", TENANT));
  }

  private JsonNode providers() {
    return parse(getBody("/providers", TENANT));
  }

  private JsonNode awaitChannels(final Predicate<JsonNode> condition) {
    final Instant deadline = Instant.now().plus(CATALOG_WAIT);
    JsonNode current = channels();
    while (!condition.test(current)) {
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException(
            "GET /channels nunca reflejo el catalogo esperado: " + current);
      }
      current = channels();
    }
    return current;
  }

  private static JsonNode channel(final JsonNode channels, final String channelType) {
    for (final JsonNode channel : channels.get("items")) {
      if (channelType.equals(channel.get("channelType").asText())) {
        return channel;
      }
    }
    throw new IllegalStateException("No aparece el canal " + channelType + " en " + channels);
  }

  private static JsonNode provider(final JsonNode providers, final String providerId) {
    for (final JsonNode provider : providers.get("items")) {
      if (providerId.equals(provider.get("providerId").asText())) {
        return provider;
      }
    }
    throw new IllegalStateException("No aparece el proveedor " + providerId + " en " + providers);
  }

  private void addChannel(final String channelType, final String... providers) {
    mongoTemplate.save(new ChannelCatalogDocument(channelType, List.of(providers), null)).block();
    awaitChannels(
        channels ->
            StreamSupport.stream(channels.get("items").spliterator(), false)
                .anyMatch(channel -> channelType.equals(channel.get("channelType").asText())));
  }

  private String acceptEmail() {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                Map.of(
                    "externalId", "catalog-" + UUID.randomUUID(),
                    "channelType", "EMAIL",
                    "recipientId", "recipient-1",
                    "recipientAddress", "alice@example.com",
                    "subject", "Asunto",
                    "body", "Cuerpo",
                    "priority", "NORMAL"))
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response.get("notificationId").toString();
  }

  private Map<String, Object> awaitStatus(final String notificationId, final String expected) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
    Map<String, Object> current = status(notificationId);
    while (Instant.now().isBefore(deadline) && !expected.equals(current.get("status"))) {
      current = status(notificationId);
    }
    return current;
  }

  private Map<String, Object> status(final String notificationId) {
    return webTestClient
        .get()
        .uri("/notifications/{id}", notificationId)
        .header("X-Tenant-Id", TENANT)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private static Notification aNotification() {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of(TENANT),
            ExternalId.of("catalog-state-" + UUID.randomUUID()),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Asunto", "Cuerpo"), Priority.NORMAL));
  }

  @Test
  void channelsShowTheConfiguredRoutingWithTheRealStateOfEachProvider() {
    final JsonNode channels = channels();

    assertEquals(
        List.of("EMAIL", "PUSH", "SMS"),
        StreamSupport.stream(channels.get("items").spliterator(), false)
            .map(channel -> channel.get("channelType").asText())
            .toList());

    final JsonNode email = channel(channels, "EMAIL");
    assertTrue(email.get("contentSchema").isNull());
    final JsonNode simulated = email.get("providers").get(0);
    assertEquals("simulated", simulated.get("providerId").asText());
    assertEquals(1, simulated.get("preferenceOrder").asInt());
    assertEquals("ENABLED", simulated.get("status").asText());
    assertTrue(simulated.get("statusReason").isNull());
    final JsonNode brevo = email.get("providers").get(1);
    assertEquals("brevo", brevo.get("providerId").asText());
    assertEquals(2, brevo.get("preferenceOrder").asInt());
    assertEquals("DISABLED", brevo.get("status").asText());
    assertEquals(
        "missing notification.provider.brevo.api-key (BREVO_API_KEY)",
        brevo.get("statusReason").asText());

    final JsonNode sms = channel(channels, "SMS");
    assertEquals(
        catalogProperties.channels().get("SMS").contentSchema(), sms.get("contentSchema").asText());
    final JsonNode twilio = sms.get("providers").get(1);
    assertEquals("twilio", twilio.get("providerId").asText());
    assertEquals("DISABLED", twilio.get("status").asText());
    assertEquals(
        "missing notification.provider.twilio.account-sid (TWILIO_ACCOUNT_SID)",
        twilio.get("statusReason").asText());

    final JsonNode fcm = channel(channels, "PUSH").get("providers").get(1);
    assertEquals("fcm", fcm.get("providerId").asText());
    assertEquals("DISABLED", fcm.get("status").asText());
    assertTrue(fcm.get("statusReason").asText().contains("FCM_CREDENTIALS_JSON"));
  }

  @Test
  void anEmailLeavesThroughTheProviderThatTheQueryShowsInFirstPosition() {
    final JsonNode preferred = channel(channels(), "EMAIL").get("providers").get(0);
    assertEquals("ENABLED", preferred.get("status").asText());

    final Map<String, Object> delivered = awaitStatus(acceptEmail(), "DELIVERED");

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals(preferred.get("providerId").asText(), delivered.get("providerId"));
  }

  @Test
  void providersListTheUnionOfAdaptersAndCatalogWithTheChannelsWhereEachIsUsed() {
    addChannel("WHATSAPP", "ghost", "simulated");

    final JsonNode providers = providers();

    assertEquals(
        List.of("brevo", "fcm", "ghost", "simulated", "twilio"),
        StreamSupport.stream(providers.get("items").spliterator(), false)
            .map(provider -> provider.get("providerId").asText())
            .toList());
    final JsonNode ghost = provider(providers, "ghost");
    assertEquals("MISSING_ADAPTER", ghost.get("status").asText());
    assertEquals(
        "no notification sender registered for this provider", ghost.get("statusReason").asText());
    assertEquals(
        parse("[{\"channelType\":\"WHATSAPP\",\"preferenceOrder\":1}]"), ghost.get("channels"));
    assertEquals(
        parse(
            "[{\"channelType\":\"EMAIL\",\"preferenceOrder\":1},"
                + "{\"channelType\":\"PUSH\",\"preferenceOrder\":1},"
                + "{\"channelType\":\"SMS\",\"preferenceOrder\":1},"
                + "{\"channelType\":\"WHATSAPP\",\"preferenceOrder\":2}]"),
        provider(providers, "simulated").get("channels"));
  }

  @Test
  void theStateShownForEachProviderIsTheStateTheDispatchFinds() {
    addChannel("WHATSAPP", "ghost");
    final Set<String> seen = new HashSet<>();

    for (final JsonNode provider : providers().get("items")) {
      final ProviderId providerId = ProviderId.of(provider.get("providerId").asText());
      final String status = provider.get("status").asText();
      seen.add(status);
      switch (status) {
        case "ENABLED" ->
            StepVerifier.create(
                    notificationSenderRegistry.resolve(providerId).send(aNotification()))
                .expectNextCount(1)
                .verifyComplete();
        case "DISABLED" ->
            StepVerifier.create(
                    notificationSenderRegistry.resolve(providerId).send(aNotification()))
                .expectError(ProviderDisabledException.class)
                .verify();
        case "MISSING_ADAPTER" ->
            assertThrows(
                ProviderNotAvailableException.class,
                () -> notificationSenderRegistry.resolve(providerId));
        default -> throw new IllegalStateException("Estado inesperado: " + status);
      }
    }

    assertEquals(Set.of("ENABLED", "DISABLED", "MISSING_ADAPTER"), seen);
  }

  @Test
  void twoTenantsReceiveIdenticalResponsesThatDoNotMentionEitherTenant() {
    for (final String path : List.of("/channels", "/providers")) {
      final String forTenant = getBody(path, TENANT);
      final String forOtherTenant = getBody(path, OTHER_TENANT);

      assertEquals(forTenant, forOtherTenant, path);
      assertFalse(forTenant.contains(TENANT), path);
      assertFalse(forOtherTenant.contains(OTHER_TENANT), path);
    }
  }

  @Test
  void noResponseContainsACredentialValueWhileTheDisabledReasonIsShown() {
    for (final String path : List.of("/channels", "/providers")) {
      final String body = getBody(path, TENANT);

      assertTrue(body.contains("TWILIO_ACCOUNT_SID"), path);
      assertFalse(body.contains(TWILIO_AUTH_TOKEN), path);
    }
  }

  @Test
  void aCatalogChangeIsVisibleWithinFiveSecondsAndOnlyOnceTheRoutingUsesIt() {
    mongoTemplate
        .save(new ChannelCatalogDocument("EMAIL", List.of("brevo", "simulated"), null))
        .block();
    final Instant savedAt = Instant.now();

    awaitChannels(
        channels ->
            "brevo"
                .equals(
                    channel(channels, "EMAIL").get("providers").get(0).get("providerId").asText()));
    final Duration elapsed = Duration.between(savedAt, Instant.now());

    assertTrue(
        elapsed.compareTo(SC005_THRESHOLD) <= 0,
        "El cambio tardo " + elapsed.toMillis() + " ms en aparecer en GET /channels");
    assertEquals(
        ProviderId.of("brevo"),
        channelCatalogPort
            .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of(TENANT))
            .block()
            .preferredProvider());
  }

  @Test
  void queryingTheCatalogChangesNothingInTheStoredCatalog() {
    final Comparator<ChannelCatalogDocument> byChannel =
        Comparator.comparing(ChannelCatalogDocument::channelType);
    final List<ChannelCatalogDocument> before =
        mongoTemplate.findAll(ChannelCatalogDocument.class).sort(byChannel).collectList().block();

    for (int round = 0; round < 3; round++) {
      channels();
      providers();
    }

    final List<ChannelCatalogDocument> after =
        mongoTemplate.findAll(ChannelCatalogDocument.class).sort(byChannel).collectList().block();
    assertEquals(before, after);
    assertTrue(matchesTheConfiguredCatalog(channels()));
  }

  @Test
  void rejectsARequestWithoutTenant() {
    webTestClient
        .get()
        .uri("/providers")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("TenantId must not be blank");
  }
}
