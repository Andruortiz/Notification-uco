package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.repository.NotificationRepository;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.config.RabbitTopologyProperties;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.provider.twilio-whatsapp.account-sid=" + WhatsAppDeliveryE2ETest.ACCOUNT_SID,
      "notification.provider.twilio-whatsapp.auth-token=" + WhatsAppDeliveryE2ETest.AUTH_TOKEN,
      "notification.provider.twilio-whatsapp.from-number=" + WhatsAppDeliveryE2ETest.FROM_NUMBER
    })
@Testcontainers
class WhatsAppDeliveryE2ETest {

  static final String ACCOUNT_SID = "AC" + "00112233445566778899aabbccddeeff";
  static final String AUTH_TOKEN = "e2e-whatsapp-secret-token";
  static final String FROM_NUMBER = "+14155238886";
  private static final String RECIPIENT = "+573001234567";
  private static final String TENANT = "tenant-1";
  private static final String OTHER_TENANT = "tenant-2";
  private static final String TEMPLATE_ID = "HX" + "0123456789abcdef0123456789abcdef";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer fakeTwilio;

  @DynamicPropertySource
  static void twilioBaseUrl(final DynamicPropertyRegistry registry) {
    fakeTwilio = FakeProviderServer.start();
    registry.add("notification.provider.twilio-whatsapp.base-url", () -> fakeTwilio.baseUrl());
  }

  @AfterAll
  static void stopFakeTwilio() {
    fakeTwilio.stop();
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private NotificationRepository notificationRepository;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private RabbitTopologyProperties rabbitTopologyProperties;

  @Autowired private ConnectionFactory connectionFactory;

  private WebTestClient webTestClient;
  private ListAppender<ILoggingEvent> logAppender;
  private RabbitAdmin rabbitAdmin;
  private String eventsListenerQueue;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    fakeTwilio.reset();
    fakeTwilio.nextResponse(201, "{\"sid\":\"SM00000000000000000000000000000001\"}");

    logAppender = new ConcurrentListAppender();
    logAppender.list = new CopyOnWriteArrayList<>();
    logAppender.start();
    rootLogger().addAppender(logAppender);

    rabbitAdmin = new RabbitAdmin(connectionFactory);
    final Queue queue = QueueBuilder.nonDurable("whatsapp-e2e-events-" + UUID.randomUUID()).build();
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue)
            .to(new FanoutExchange(rabbitTopologyProperties.eventsExchange())));
    eventsListenerQueue = queue.getName();
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(logAppender);
    rabbitAdmin.deleteQueue(eventsListenerQueue);
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private void preferProviders(final String... providers) {
    final ChannelCatalogDocument seeded =
        mongoTemplate.findById("WHATSAPP", ChannelCatalogDocument.class).block();
    assertNotNull(seeded, "el catalogo sembrado debe declarar el canal WHATSAPP");
    mongoTemplate
        .save(new ChannelCatalogDocument("WHATSAPP", List.of(providers), seeded.contentSchema()))
        .block();
    final ProviderId expected = ProviderId.of(providers[0]);
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort
              .findActiveRoute(ChannelType.of("WHATSAPP"), TenantId.of(TENANT))
              .block();
      if (route != null && route.preferredProvider().equals(expected)) {
        return;
      }
    }
    throw new IllegalStateException("El catalogo nunca prefirio " + providers[0]);
  }

  private Map<String, Object> textRequest(
      final String externalId, final String recipient, final String subject, final String body) {
    final Map<String, Object> request = baseRequest(externalId, "WHATSAPP", recipient);
    request.put("body", body);
    if (subject != null) {
      request.put("subject", subject);
    }
    return request;
  }

  private Map<String, Object> templateRequest(
      final String externalId, final Map<String, String> variables) {
    final Map<String, Object> request = baseRequest(externalId, "WHATSAPP", RECIPIENT);
    request.put("providerTemplate", Map.of("templateId", TEMPLATE_ID, "variables", variables));
    return request;
  }

  private static Map<String, Object> baseRequest(
      final String externalId, final String channel, final String recipient) {
    final Map<String, Object> request = new HashMap<>();
    request.put("externalId", externalId);
    request.put("channelType", channel);
    request.put("recipientId", "recipient-1");
    request.put("recipientAddress", recipient);
    request.put("priority", "NORMAL");
    return request;
  }

  private String accept(final Map<String, Object> request) {
    return accept(TENANT, request);
  }

  private String accept(final String tenant, final Map<String, Object> request) {
    final Map<String, Object> response =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(tenant))
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

  private Map<String, Object> rejected(final Map<String, Object> request) {
    final Map<String, Object> error =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(TENANT))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertNotNull(error);
    return error;
  }

  private Map<String, Object> status(final String notificationId) {
    return webTestClient
        .get()
        .uri("/notifications/detail?notificationId={id}", notificationId)
        .header("Authorization", TestTokens.bearer(TENANT))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  private Map<String, Object> awaitStatus(final String notificationId, final String expected) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    Map<String, Object> current = status(notificationId);
    while (Instant.now().isBefore(deadline)
        && !expected.equals(String.valueOf(current.get("status")))) {
      current = status(notificationId);
    }
    return current;
  }

  private static Map<String, String> form(final String body) {
    return Arrays.stream(body.split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
  }

  private String logs() {
    return logAppender.list.stream().map(LogLines::render).collect(Collectors.joining("\n"));
  }

  private List<String> publishedEvents() {
    final List<String> bodies = new ArrayList<>();
    Message message = rabbitTemplate.receive(eventsListenerQueue, 3_000);
    while (message != null) {
      bodies.add(new String(message.getBody(), StandardCharsets.UTF_8));
      message = rabbitTemplate.receive(eventsListenerQueue, 500);
    }
    return bodies;
  }

  private boolean persisted(final String externalId) {
    return notificationRepository
            .findByTenantAndExternalId(TenantId.of(TENANT), ExternalId.of(externalId))
            .block()
        != null;
  }

  @Test
  void theSeededCatalogDeclaresTheWhatsAppChannelWithBothProviders() {
    final ChannelRoute route =
        channelCatalogPort.findActiveRoute(ChannelType.of("WHATSAPP"), TenantId.of(TENANT)).block();

    assertNotNull(route);
    assertEquals(
        java.util.Set.of(ProviderId.of("simulated"), ProviderId.of("twilio-whatsapp")),
        java.util.Set.copyOf(route.providers()));
    assertTrue(route.contentSchema().contains("\"maxLength\":1600"));
    assertTrue(route.contentSchema().contains("providerTemplate"));
  }

  @Test
  void deliversFreeTextThroughTwilioWithoutTheSubjectAndRecordsItsProviderId() {
    preferProviders("twilio-whatsapp", "simulated");

    final String notificationId =
        accept(textRequest("wa-ok-1", RECIPIENT, "AsuntoQueNoViaja", "Tu pedido va en camino"));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("DELIVERED", delivered.get("status"));
    assertEquals("twilio-whatsapp", delivered.get("providerId"));
    assertEquals(1, fakeTwilio.requests().size());
    final FakeProviderServer.RecordedRequest request = fakeTwilio.requests().get(0);
    assertEquals("/2010-04-01/Accounts/" + ACCOUNT_SID + "/Messages.json", request.path());
    assertEquals(
        Map.of(
            "To",
            "whatsapp:" + RECIPIENT,
            "From",
            "whatsapp:" + FROM_NUMBER,
            "Body",
            "Tu pedido va en camino"),
        form(request.body()));
    assertFalse(request.body().contains("AsuntoQueNoViaja"));
  }

  @Test
  void doesNotCallTwilioWhenTheSimulatedProviderIsPreferred() {
    preferProviders("simulated", "twilio-whatsapp");

    final String notificationId = accept(textRequest("wa-sim-1", RECIPIENT, null, "Hola"));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("simulated", delivered.get("providerId"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void deliversAProviderTemplateWithItsVariablesAndWithoutAFreeBody() {
    preferProviders("twilio-whatsapp", "simulated");

    final String notificationId =
        accept(templateRequest("wa-tpl-1", Map.of("1", "Alice", "2", "42")));
    final Map<String, Object> delivered = awaitStatus(notificationId, "DELIVERED");

    assertEquals("twilio-whatsapp", delivered.get("providerId"));
    assertEquals(1, fakeTwilio.requests().size());
    final Map<String, String> form = form(fakeTwilio.requests().get(0).body());
    assertEquals(TEMPLATE_ID, form.get("ContentSid"));
    assertTrue(form.get("ContentVariables").contains("\"1\":\"Alice\""));
    assertTrue(form.get("ContentVariables").contains("\"2\":\"42\""));
    assertFalse(form.containsKey("Body"));
  }

  @Test
  void aBodyTogetherWithAProviderTemplateIsRejectedWithoutPersistingOrCallingTwilio() {
    preferProviders("twilio-whatsapp", "simulated");
    final Map<String, Object> request = templateRequest("wa-both-1", Map.of("1", "Alice"));
    request.put("body", "Hola");

    rejected(request);

    assertFalse(persisted("wa-both-1"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void neitherABodyNorAProviderTemplateIsRejectedWithoutPersistingOrCallingTwilio() {
    preferProviders("twilio-whatsapp", "simulated");

    rejected(baseRequest("wa-none-1", "WHATSAPP", RECIPIENT));

    assertFalse(persisted("wa-none-1"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void aProviderTemplateOnTheEmailChannelIsRejectedNamingTheMissingSupport() {
    final Map<String, Object> request = baseRequest("wa-email-1", "EMAIL", "alice@example.com");
    request.put("providerTemplate", Map.of("templateId", TEMPLATE_ID, "variables", Map.of()));

    final Map<String, Object> error = rejected(request);

    assertTrue(
        String.valueOf(error.get("message"))
            .contains("channel does not accept provider templates"));
    assertFalse(persisted("wa-email-1"));
  }

  @Test
  void aProviderTemplateWithMoreThanTwentyVariablesIsRejectedAtAcceptance() {
    final Map<String, String> variables = new HashMap<>();
    for (int index = 0; index < 21; index++) {
      variables.put(String.valueOf(index), "v");
    }

    rejected(templateRequest("wa-many-vars-1", variables));

    assertFalse(persisted("wa-many-vars-1"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void freeTextOutsideTheSessionWindowEndsInFailedWithASingleRequestAndNoRetries() {
    preferProviders("twilio-whatsapp", "simulated");
    fakeTwilio.nextResponse(400, "{\"code\":63016,\"message\":\"outside window\",\"status\":400}");

    final String notificationId = accept(textRequest("wa-window-1", RECIPIENT, null, "Hola"));

    assertEquals("FAILED", awaitStatus(notificationId, "FAILED").get("status"));
    assertEquals(1, fakeTwilio.requests().size());
    assertTrue(logs().contains("reason=outside-session-window"));
  }

  @Test
  void aTemporaryProviderFailureEndsInRecoverableWithOneAttemptPerProviderCall() {
    preferProviders("twilio-whatsapp", "simulated");
    fakeTwilio.nextResponse(500, "{\"code\":20500,\"status\":500}");

    final String notificationId = accept(textRequest("wa-500-1", RECIPIENT, null, "Hola"));

    assertEquals("RECOVERABLE", awaitStatus(notificationId, "RECOVERABLE").get("status"));
    assertEquals(1, fakeTwilio.requests().size());
    fakeTwilio
        .requests()
        .forEach(
            request ->
                assertTrue(
                    request.headers().keySet().stream()
                        .noneMatch(name -> name.toLowerCase().contains("idempotency"))));
  }

  private static final String RECIPIENT_ID = "recipient-1";

  private Map<String, Object> history(final String recipientId) {
    return webTestClient
        .get()
        .uri("/notifications?recipientId={id}&channelType=WHATSAPP", recipientId)
        .header("Authorization", TestTokens.bearer(TENANT))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  @Test
  void theAttemptHistoryRecordsTheProviderAndTheCategoryOfEachAttempt() {
    preferProviders("twilio-whatsapp", "simulated");
    fakeTwilio.nextResponse(400, "{\"code\":63024,\"status\":400}");

    final String notificationId = accept(textRequest("wa-history-1", RECIPIENT, null, "Hola"));
    awaitStatus(notificationId, "FAILED");

    final List<Map<String, Object>> items =
        (List<Map<String, Object>>) history(RECIPIENT_ID).get("items");
    final Map<String, Object> item =
        items.stream()
            .filter(candidate -> notificationId.equals(candidate.get("notificationId")))
            .findFirst()
            .orElseThrow();
    final List<Map<String, Object>> attempts =
        (List<Map<String, Object>>) item.get("deliveryAttempts");
    assertEquals(1, attempts.size());
    assertEquals("twilio-whatsapp", attempts.get(0).get("providerId"));
    assertEquals("PERMANENT_FAILURE", attempts.get(0).get("result"));
  }

  @Test
  void aBodyOfExactly1600CharactersIsAcceptedAndOneOfMoreIsRejectedWithoutPersisting() {
    preferProviders("twilio-whatsapp", "simulated");

    final String acceptedId = accept(textRequest("wa-1600-1", RECIPIENT, null, "a".repeat(1600)));
    assertEquals("DELIVERED", awaitStatus(acceptedId, "DELIVERED").get("status"));
    final int requestsBefore = fakeTwilio.requests().size();

    final Map<String, Object> error =
        rejected(textRequest("wa-1601-1", RECIPIENT, null, "a".repeat(1601)));

    assertTrue(
        String.valueOf(error.get("message")).matches("(?s).*1[.,]?600.*"), String.valueOf(error));
    assertFalse(persisted("wa-1601-1"));
    assertEquals(requestsBefore, fakeTwilio.requests().size());
  }

  @Test
  void anAttachmentIsRejectedBecauseTheChannelDoesNotDeclareAttachments() {
    final Map<String, Object> request = textRequest("wa-att-1", RECIPIENT, null, "Hola");
    request.put(
        "attachments",
        List.of(
            Map.of(
                "fileName",
                "a.png",
                "contentType",
                "image/png",
                "sizeBytes",
                3,
                "content",
                "AAAA")));

    final Map<String, Object> error = rejected(request);

    assertTrue(
        String.valueOf(error.get("message")).contains("does not accept attachments"),
        String.valueOf(error));
    assertFalse(persisted("wa-att-1"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void aRecipientWithoutInternationalFormatEndsInFailedWithoutCallingTwilio() {
    preferProviders("twilio-whatsapp", "simulated");

    final String notificationId =
        accept(textRequest("wa-bad-recipient-1", "alice@example.com", null, "Hola"));

    assertEquals("FAILED", awaitStatus(notificationId, "FAILED").get("status"));
    assertTrue(fakeTwilio.requests().isEmpty());
  }

  @Test
  void aProviderTemplateOfOneTenantIsNeverVisibleToAnotherTenant() {
    preferProviders("twilio-whatsapp", "simulated");
    final String secretVariable = "ValorSecretoDelTenantUno";
    final String notificationId =
        accept(templateRequest("wa-tenant-1", Map.of("1", secretVariable)));
    awaitStatus(notificationId, "DELIVERED");

    webTestClient
        .get()
        .uri("/notifications/detail?notificationId={id}", notificationId)
        .header("Authorization", TestTokens.bearer(OTHER_TENANT))
        .exchange()
        .expectStatus()
        .isNotFound();
    final String otherSearch =
        new String(
            webTestClient
                .get()
                .uri("/notifications?channelType=WHATSAPP")
                .header("Authorization", TestTokens.bearer(OTHER_TENANT))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody(),
            StandardCharsets.UTF_8);
    assertFalse(otherSearch.contains(notificationId));
    assertFalse(otherSearch.contains(secretVariable));

    assertEquals("DELIVERED", status(notificationId).get("status"));
    assertTrue(logs().contains("tenantId=" + TENANT));
    assertFalse(logs().contains(secretVariable));
  }

  @Test
  void noSecretBodyVariableOrFullNumberLeaksIntoLogsOrPublishedEvents() {
    preferProviders("twilio-whatsapp", "simulated");
    final String secretBody = "CuerpoSecretoWhatsApp";
    final String secretVariable = "VariableSecretaWhatsApp";

    final String textId = accept(textRequest("wa-noleak-1", RECIPIENT, null, secretBody));
    awaitStatus(textId, "DELIVERED");
    final String templateId = accept(templateRequest("wa-noleak-2", Map.of("1", secretVariable)));
    awaitStatus(templateId, "DELIVERED");

    final String providerMessage = "The 'To' number " + RECIPIENT + " is not a valid phone number.";
    fakeTwilio.nextResponse(
        400, "{\"code\":63024,\"message\":\"" + providerMessage + "\",\"status\":400}");
    final String rejectedId = accept(textRequest("wa-noleak-3", RECIPIENT, null, secretBody));
    awaitStatus(rejectedId, "FAILED");

    final String basicCredentials =
        Base64.getEncoder()
            .encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.ISO_8859_1));
    final List<String> forbidden =
        List.of(
            ACCOUNT_SID,
            AUTH_TOKEN,
            basicCredentials,
            secretBody,
            secretVariable,
            RECIPIENT,
            "not a valid");

    final String logs = logs();
    assertTrue(logs.contains("recipient=***4567"), "the masked number must be logged");
    assertTrue(logs.contains("providerMessageId=SM0000"), "the provider message id must be logged");
    assertTrue(logs.contains("providerErrorCode=63024"), "the provider error code must be logged");
    forbidden.forEach(
        value -> assertFalse(logs.contains(value), "logs must not contain a forbidden value"));

    final List<String> events = publishedEvents();
    assertFalse(events.isEmpty(), "domain events must have been published");
    events.forEach(
        event ->
            forbidden.forEach(
                value ->
                    assertFalse(
                        event.contains(value), "events must not contain a forbidden value")));
  }

  @Test
  void aSecondNotificationWithTheSameExternalIdIsADuplicateAndSendsNothingMore() {
    preferProviders("twilio-whatsapp", "simulated");

    final String first = accept(textRequest("wa-dup-1", RECIPIENT, null, "Hola"));
    awaitStatus(first, "DELIVERED");
    final String second = accept(textRequest("wa-dup-1", RECIPIENT, null, "Hola"));

    assertEquals(first, second);
    assertEquals(1, fakeTwilio.requests().size());
  }
}
