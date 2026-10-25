package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.infrastructure.adapter.out.catalog.ChannelCatalogDocument;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.DeviceDocument;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument;
import co.edu.uco.notification.infrastructure.adapter.out.provider.FakeProviderServer;
import co.edu.uco.notification.infrastructure.adapter.out.provider.FcmTestCredentials;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.ConcurrentListAppender;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
      DeviceRegistryE2ETest.TOKEN_KEY_PROPERTY,
      DeviceRegistryE2ETest.FINGERPRINT_KEY_PROPERTY
    })
@Testcontainers
class DeviceRegistryE2ETest {

  static final String TOKEN_KEY_PROPERTY =
      "DEVICE_TOKEN_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
  static final String FINGERPRINT_KEY_PROPERTY =
      "DEVICE_FINGERPRINT_KEY=ZmluZ2VycHJpbnQta2V5LWZvci10ZXN0cy0wMDAwMDAwMDA=";

  private static final String ACCESS_TOKEN = "device-e2e-access-" + "token";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static FakeProviderServer fakeAuthorization;
  static FakeProviderServer fakeFcm;

  @DynamicPropertySource
  static void fcmProperties(final DynamicPropertyRegistry registry) {
    fakeAuthorization = FakeProviderServer.start();
    fakeAuthorization.nextResponse(
        200, "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":3600}");
    fakeFcm = FakeProviderServer.start();
    registry.add("notification.provider.fcm.credentials-json", FcmTestCredentials.shared()::json);
    registry.add("notification.provider.fcm.base-url", () -> fakeFcm.baseUrl());
    registry.add(
        "notification.provider.fcm.token-url", () -> fakeAuthorization.baseUrl() + "/token");
  }

  @AfterAll
  static void stopFakeServers() {
    fakeAuthorization.stop();
    fakeFcm.stop();
  }

  @LocalServerPort private int port;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  private WebTestClient web;
  private ConcurrentListAppender appender;
  private String tenant;
  private String tenantB;

  @BeforeEach
  void setUp() {
    web =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
    final int n = SEQUENCE.incrementAndGet();
    tenant = "dev-tenant-a-" + n;
    tenantB = "dev-tenant-b-" + n;
    fakeFcm.reset();
    fakeFcm.nextResponse(200, "{\"name\":\"projects/demo-project/messages/0:e2e-0001\"}");
    appender = new ConcurrentListAppender();
    appender.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
    appender.stop();
  }

  private static String token(final String label) {
    return "fcm-" + label + "-" + SEQUENCE.incrementAndGet() + "-WXYZ";
  }

  private static String mask(final String token) {
    return "****" + token.substring(token.length() - 4);
  }

  private WebTestClient.ResponseSpec register(
      final String tenantId,
      final Role role,
      final String sessionSubject,
      final String token,
      final String bodySubject) {
    final Map<String, Object> body = new HashMap<>();
    body.put("platform", "ANDROID");
    body.put("token", token);
    if (bodySubject != null) {
      body.put("subject", bodySubject);
    }
    return web.post()
        .uri("/devices")
        .header("Authorization", TestTokens.bearer(tenantId, role, sessionSubject))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private Map<String, Object> registerOk(
      final String tenantId,
      final Role role,
      final String sessionSubject,
      final String token,
      final int expectedStatus) {
    final Map<String, Object> response =
        register(tenantId, role, sessionSubject, token, null)
            .expectStatus()
            .isEqualTo(expectedStatus)
            .expectBody(MAP)
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(
      final String tenantId, final Role role, final String sessionSubject, final String subject) {
    final Map<String, Object> response =
        web.get()
            .uri(
                uri -> {
                  uri.path("/devices");
                  if (subject != null) {
                    uri.queryParam("subject", subject);
                  }
                  return uri.build();
                })
            .header("Authorization", TestTokens.bearer(tenantId, role, sessionSubject))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(MAP)
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return (List<Map<String, Object>>) response.get("items");
  }

  private WebTestClient.ResponseSpec remove(
      final String tenantId, final Role role, final String sessionSubject, final String deviceId) {
    return web.post()
        .uri("/device-removals")
        .header("Authorization", TestTokens.bearer(tenantId, role, sessionSubject))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("deviceId", deviceId))
        .exchange();
  }

  private void prefer(final String provider) {
    final ChannelCatalogDocument seeded =
        mongoTemplate.findById("PUSH", ChannelCatalogDocument.class).block();
    assertNotNull(seeded);
    mongoTemplate
        .save(new ChannelCatalogDocument("PUSH", List.of(provider), seeded.contentSchema()))
        .block();
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final ChannelRoute route =
          channelCatalogPort.findActiveRoute(ChannelType.of("PUSH"), TenantId.of(tenant)).block();
      if (route != null
          && route.providers().size() == 1
          && route.preferredProvider().equals(ProviderId.of(provider))) {
        return;
      }
      sleep(100);
    }
    throw new IllegalStateException("catalog never preferred " + provider);
  }

  private String sendPush(final String tenantId, final String token, final String recipientId) {
    final Map<String, Object> request = new HashMap<>();
    request.put("externalId", "push-" + SEQUENCE.incrementAndGet());
    request.put("channelType", "PUSH");
    request.put("recipientId", recipientId);
    request.put("recipientAddress", token);
    request.put("body", "Hola");
    request.put("priority", "NORMAL");
    final Map<String, Object> response =
        web.post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer(tenantId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(MAP)
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response.get("notificationId").toString();
  }

  private Instant awaitStatus(final String tenantId, final String id, final String expected) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(deadline)) {
      final Map<String, Object> current =
          web.get()
              .uri("/notifications/detail?notificationId={id}", id)
              .header("Authorization", TestTokens.bearer(tenantId))
              .exchange()
              .expectBody(MAP)
              .returnResult()
              .getResponseBody();
      if (current != null && expected.equals(String.valueOf(current.get("status")))) {
        return Instant.now();
      }
      sleep(50);
    }
    throw new IllegalStateException("notification never reached " + expected);
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private String logs() {
    final List<ILoggingEvent> events;
    synchronized (appender.list) {
      events = new ArrayList<>(appender.list);
    }
    return events.stream().map(LogLines::render).collect(Collectors.joining("\n"));
  }

  private static String fcmError(final int code, final String status, final String errorCode) {
    return "{\"error\":{\"code\":"
        + code
        + ",\"message\":\"x\",\"status\":\""
        + status
        + "\",\"details\":[{\"errorCode\":\""
        + errorCode
        + "\"}]}}";
  }

  @Test
  void registersListsAndRemovesWithMaskingByRoleAndEncryptionAtRest() {
    final String token = token("flow");

    final Map<String, Object> created = registerOk(tenant, Role.CLIENTE, "app-1", token, 201);
    final Map<String, Object> existing = registerOk(tenant, Role.CLIENTE, "app-1", token, 200);

    assertEquals(mask(token), created.get("token"));
    assertEquals("app-1", created.get("originComponent"));
    assertEquals(created.get("deviceId"), existing.get("deviceId"));
    assertEquals(created.get("recipientId"), existing.get("recipientId"));
    final DeviceDocument stored =
        mongoTemplate.findById(created.get("deviceId").toString(), DeviceDocument.class).block();
    assertNotNull(stored);
    assertFalse(stored.protectedToken().contains(token));
    assertEquals(1, list(tenant, Role.CLIENTE, "app-1", null).size());
    assertEquals(mask(token), list(tenant, Role.CLIENTE, "app-1", null).get(0).get("token"));
    assertEquals(token, list(tenant, Role.ADMINISTRADOR, "admin", "app-1").get(0).get("token"));

    remove(tenant, Role.CLIENTE, "app-1", created.get("deviceId").toString())
        .expectStatus()
        .isNoContent();
    assertTrue(list(tenant, Role.CLIENTE, "app-1", null).isEmpty());
    remove(tenant, Role.CLIENTE, "app-1", created.get("deviceId").toString())
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NTF-3041");
  }

  @Test
  void aSessionCannotActOnAnotherSubjectButAnAdministratorCan() {
    final String token = token("subject");

    register(tenant, Role.CLIENTE, "app-1", token, "app-2").expectStatus().isForbidden();
    register(tenant, Role.OPERADOR, "ops-1", token, "app-2").expectStatus().isForbidden();
    web.get()
        .uri("/devices?subject=app-2")
        .header("Authorization", TestTokens.bearer(tenant, Role.CLIENTE, "app-1"))
        .exchange()
        .expectStatus()
        .isForbidden();
    register(tenant, Role.ADMINISTRADOR, "admin", token, "app-2")
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.originComponent")
        .isEqualTo("admin");
    assertEquals(1, list(tenant, Role.ADMINISTRADOR, "admin", "app-2").size());
    assertTrue(list(tenant, Role.CLIENTE, "app-1", null).isEmpty());
  }

  @Test
  void nothingCrossesBetweenTenantsOrBetweenSubjectsOfTheSameTenant() {
    final String token = token("iso");
    final Map<String, Object> a1 = registerOk(tenant, Role.CLIENTE, "app-1", token, 201);
    final Map<String, Object> b1 = registerOk(tenantB, Role.CLIENTE, "app-1", token, 201);

    assertNotEquals(a1.get("deviceId"), b1.get("deviceId"));
    assertNotEquals(a1.get("recipientId"), b1.get("recipientId"));

    final WebTestClient.ResponseSpec foreignTenant =
        remove(tenantB, Role.CLIENTE, "app-1", a1.get("deviceId").toString());
    foreignTenant.expectStatus().isNotFound();
    final WebTestClient.ResponseSpec foreignSubject =
        remove(tenant, Role.CLIENTE, "app-2", a1.get("deviceId").toString());
    foreignSubject.expectStatus().isNotFound();
    final Map<String, Object> missing =
        remove(tenant, Role.CLIENTE, "app-1", "missing-device")
            .expectStatus()
            .isNotFound()
            .expectBody(MAP)
            .returnResult()
            .getResponseBody();
    assertEquals("NTF-3041", missing.get("code"));
    assertEquals(1, list(tenant, Role.CLIENTE, "app-1", null).size());
    assertEquals(1, list(tenantB, Role.CLIENTE, "app-1", null).size());
    assertTrue(list(tenant, Role.CLIENTE, "app-2", null).isEmpty());

    final Map<String, Object> moved = registerOk(tenant, Role.CLIENTE, "app-2", token, 200);
    assertEquals(a1.get("deviceId"), moved.get("deviceId"));
    assertTrue(list(tenant, Role.CLIENTE, "app-1", null).isEmpty());
    assertEquals(1, list(tenant, Role.CLIENTE, "app-2", null).size());
    assertEquals(1, list(tenantB, Role.CLIENTE, "app-1", null).size());
  }

  @Test
  void erasureByHolderIsAdministratorOnlyAndLeavesOthersUntouched() {
    registerOk(tenant, Role.CLIENTE, "app-1", token("er1"), 201);
    registerOk(tenant, Role.CLIENTE, "app-1", token("er2"), 201);
    registerOk(tenant, Role.CLIENTE, "app-2", token("er3"), 201);
    registerOk(tenantB, Role.CLIENTE, "app-1", token("er4"), 201);

    web.post()
        .uri("/device-erasures")
        .header("Authorization", TestTokens.bearer(tenant, Role.CLIENTE, "app-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("subject", "app-1"))
        .exchange()
        .expectStatus()
        .isForbidden();
    assertEquals(2, list(tenant, Role.CLIENTE, "app-1", null).size());

    web.post()
        .uri("/device-erasures")
        .header("Authorization", TestTokens.bearer(tenant, Role.ADMINISTRADOR, "admin"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("subject", "app-1"))
        .exchange()
        .expectStatus()
        .isNoContent();

    assertTrue(list(tenant, Role.CLIENTE, "app-1", null).isEmpty());
    assertEquals(1, list(tenant, Role.CLIENTE, "app-2", null).size());
    assertEquals(1, list(tenantB, Role.CLIENTE, "app-1", null).size());
    assertEquals(
        2,
        mongoTemplate
            .count(
                Query.query(Criteria.where("tenantId").in(tenant, tenantB)), DeviceDocument.class)
            .block());
  }

  @Test
  void anUnregisteredTokenDisappearsWithinFiveSecondsOfTheFailedAttemptAndOnlyThen() {
    prefer("fcm");
    final String token = token("gone");
    final String other = token("keep");
    registerOk(tenant, Role.CLIENTE, "app-1", token, 201);
    registerOk(tenant, Role.CLIENTE, "app-1", other, 201);
    registerOk(tenantB, Role.CLIENTE, "app-1", token, 201);

    fakeFcm.nextResponse(404, fcmError(404, "NOT_FOUND", "NOT_FOUND"));
    awaitStatus(tenant, sendPush(tenant, token, "rec-gone"), "FAILED");
    fakeFcm.nextResponse(503, fcmError(503, "UNAVAILABLE", "UNAVAILABLE"));
    awaitStatus(tenant, sendPush(tenant, token, "rec-gone"), "RECOVERABLE");
    sleep(1_500);
    assertEquals(2, list(tenant, Role.CLIENTE, "app-1", null).size());

    fakeFcm.nextResponse(404, fcmError(404, "NOT_FOUND", "UNREGISTERED"));
    final String notificationId = sendPush(tenant, token, "rec-gone");
    final Instant failedAt = awaitStatus(tenant, notificationId, "FAILED");
    Instant goneAt = null;
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (goneAt == null && Instant.now().isBefore(deadline)) {
      if (list(tenant, Role.CLIENTE, "app-1", null).size() == 1) {
        goneAt = Instant.now();
      } else {
        sleep(50);
      }
    }

    assertNotNull(goneAt, "the invalid device must be removed");
    assertTrue(
        Duration.between(failedAt, goneAt).compareTo(Duration.ofSeconds(5)) < 0,
        "the device must disappear in under 5 seconds");
    assertEquals(mask(other), list(tenant, Role.CLIENTE, "app-1", null).get(0).get("token"));
    assertEquals(1, list(tenantB, Role.CLIENTE, "app-1", null).size());
    final Map<String, Object> timeline =
        web.get()
            .uri("/notifications/timeline?notificationId={id}", notificationId)
            .header("Authorization", TestTokens.bearer(tenant))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(MAP)
            .returnResult()
            .getResponseBody();
    assertTrue(String.valueOf(timeline).contains("INVALID_RECIPIENT"));
  }

  @Test
  void aGoneTokenIsAlsoRemovedAndAnAcceptedSendRefreshesLastSeenAtOncePerDay() {
    prefer("fcm");
    final String token = token("seen");
    final String gone = token("gone410");
    final Map<String, Object> device = registerOk(tenant, Role.CLIENTE, "app-1", token, 201);
    registerOk(tenant, Role.CLIENTE, "app-1", gone, 201);
    mongoTemplate
        .updateFirst(
            Query.query(Criteria.where("_id").is(device.get("deviceId"))),
            new org.springframework.data.mongodb.core.query.Update()
                .set("lastSeenAt", Instant.now().minus(Duration.ofDays(3))),
            DeviceDocument.class)
        .block();

    awaitStatus(tenant, sendPush(tenant, token, "rec-seen"), "DELIVERED");
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    Instant seen = lastSeen(device.get("deviceId").toString());
    while (seen.isBefore(Instant.now().minus(Duration.ofHours(1)))
        && Instant.now().isBefore(deadline)) {
      sleep(100);
      seen = lastSeen(device.get("deviceId").toString());
    }
    assertTrue(seen.isAfter(Instant.now().minus(Duration.ofMinutes(5))));

    fakeFcm.nextResponse(410, "{}");
    awaitStatus(tenant, sendPush(tenant, gone, "rec-410"), "FAILED");
    final Instant removalDeadline = Instant.now().plus(Duration.ofSeconds(10));
    while (list(tenant, Role.CLIENTE, "app-1", null).size() > 1
        && Instant.now().isBefore(removalDeadline)) {
      sleep(50);
    }
    assertEquals(1, list(tenant, Role.CLIENTE, "app-1", null).size());
  }

  private Instant lastSeen(final String deviceId) {
    return mongoTemplate.findById(deviceId, DeviceDocument.class).block().lastSeenAt();
  }

  @Test
  void pushRecipientsAreEncryptedMaskedForNonAdministratorsAndStillFilterable() {
    prefer("fcm");
    final String token = token("push");
    final String recipient = "recipient-visible-" + SEQUENCE.incrementAndGet();
    final String notificationId = sendPush(tenant, token, recipient);
    awaitStatus(tenant, notificationId, "DELIVERED");

    final NotificationDocument stored =
        mongoTemplate.findById(notificationId, NotificationDocument.class).block();
    assertNotNull(stored);
    assertFalse(stored.recipientAddress().contains(token));
    assertFalse(stored.recipientId().contains(recipient));
    assertNotNull(stored.recipientIdFingerprint());

    final Map<String, Object> asOperator = search(Role.OPERADOR, recipient);
    final Map<String, Object> asAdmin = search(Role.ADMINISTRADOR, recipient);
    assertTrue(String.valueOf(asOperator).contains("recipientId=****"));
    assertFalse(String.valueOf(asOperator).contains(recipient));
    assertTrue(String.valueOf(asAdmin).contains("recipientId=" + recipient));
    assertEquals(token, fakeFcmSentToken());
  }

  private String fakeFcmSentToken() {
    final String body = fakeFcm.requests().get(0).body();
    final int start = body.indexOf("\"token\":\"") + "\"token\":\"".length();
    return body.substring(start, body.indexOf('"', start));
  }

  private Map<String, Object> search(final Role role, final String recipientId) {
    final Map<String, Object> response =
        web.get()
            .uri("/notifications?recipientId={id}&channelType=PUSH", recipientId)
            .header("Authorization", TestTokens.bearer(tenant, role, "reader"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(MAP)
            .returnResult()
            .getResponseBody();
    assertNotNull(response);
    return response;
  }

  @Test
  void theFullTokenNeverReachesTheLogsWhileTheMaskedFormDoes() {
    prefer("fcm");
    final String token = token("logs");
    registerOk(tenant, Role.CLIENTE, "app-1", token, 201);
    awaitStatus(tenant, sendPush(tenant, token, "rec-logs"), "DELIVERED");
    fakeFcm.nextResponse(404, fcmError(404, "NOT_FOUND", "UNREGISTERED"));
    awaitStatus(tenant, sendPush(tenant, token, "rec-logs"), "FAILED");
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (!list(tenant, Role.CLIENTE, "app-1", null).isEmpty()
        && Instant.now().isBefore(deadline)) {
      sleep(50);
    }

    final String logs = logs();
    assertTrue(logs.contains("recipient=***" + token.substring(token.length() - 4)));
    assertTrue(logs.contains("Device removed"));
    assertFalse(logs.contains(token), "the full token must never be logged");
  }

  @Test
  void registrationListingAndRemovalStayUnderThreeHundredMillisAtP95() {
    final List<Long> samples = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      registerOk(tenant, Role.CLIENTE, "perf", token("perf-base"), 201);
    }
    for (int i = 0; i < 55; i++) {
      final String token = token("perf-loop");
      final long t0 = System.nanoTime();
      final Map<String, Object> created = registerOk(tenant, Role.CLIENTE, "perf", token, 201);
      final long t1 = System.nanoTime();
      list(tenant, Role.CLIENTE, "perf", null);
      final long t2 = System.nanoTime();
      remove(tenant, Role.CLIENTE, "perf", created.get("deviceId").toString())
          .expectStatus()
          .isNoContent();
      final long t3 = System.nanoTime();
      if (i >= 5) {
        samples.add(Duration.ofNanos(t1 - t0).toMillis());
        samples.add(Duration.ofNanos(t2 - t1).toMillis());
        samples.add(Duration.ofNanos(t3 - t2).toMillis());
      }
    }

    samples.sort(Long::compare);
    final long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
    assertEquals(150, samples.size());
    assertTrue(p95 < 300, "p95 was " + p95 + " ms");
  }

  @Test
  void malformedRequestsAreRejected() {
    register(tenant, Role.CLIENTE, "app-1", " ", null).expectStatus().isBadRequest();
    register(tenant, Role.CLIENTE, "app-1", "x".repeat(4097), null).expectStatus().isBadRequest();
    web.post()
        .uri("/devices")
        .header("Authorization", TestTokens.bearer(tenant, Role.CLIENTE, "app-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("platform", "SYMBIAN", "token", token("bad")))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
