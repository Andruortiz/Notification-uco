package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.contract.ApiContract;
import co.edu.uco.notification.infrastructure.support.StubSecurityPlatform;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.profiles.active=publico",
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.auth.jwt.hs256-secret=public-routes-e2e-secret-0123456789abcdef-0123",
      "security.enabled=true",
      "security.pep.enforcement.enabled=true",
      "security.pep.enforcement.allow-insecure-http=true",
      "security.pep.enforcement.application-name=notification-service",
      "security.pep.enforcement.environment=test",
      "security.pep.enforcement.application-credential=test-credential"
    })
@Testcontainers
class PublicRoutesPepE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final StubSecurityPlatform PLATFORM = new StubSecurityPlatform();

  @DynamicPropertySource
  static void platformUrls(final DynamicPropertyRegistry registry) {
    registry.add("security.pep.enforcement.pep-url", PLATFORM::baseUrl);
    registry.add("notification.auth.session.url", PLATFORM::baseUrl);
  }

  @AfterAll
  static void stopPlatform() {
    PLATFORM.close();
  }

  @LocalServerPort private int port;

  @LocalManagementPort private int managementPort;

  private WebTestClient client;
  private WebTestClient management;

  @BeforeEach
  void setUp() {
    PLATFORM.reset();
    PLATFORM.withSession("cookie-public", "tenant-public");
    client = clientOn(port);
    management = clientOn(managementPort);
  }

  private static WebTestClient clientOn(final int port) {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
  }

  @Test
  void theContractIsDownloadableWithoutCredentials() {
    final String body =
        client
            .get()
            .uri("/openapi/api-notificaciones.yaml")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertTrue(body.contains("openapi: 3.1.0"));
    assertTrue(body.contains("x-public-routes"));
  }

  @Test
  void theGeneratedDocumentationAndItsConfigurationAreReadableWithoutCredentials() {
    client.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
    client.get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void everyBusinessOperationOfTheContractIsRejectedWithoutCredentials() {
    for (final ApiContract.Operation operation : ApiContract.operations()) {
      client
          .method(HttpMethod.valueOf(operation.method()))
          .uri(operation.path())
          .exchange()
          .expectStatus()
          .isUnauthorized();
    }
  }

  @Test
  void aSessionCookieStillReachesTheBusinessRoutes() {
    final EntityExchangeResult<byte[]> result =
        client
            .get()
            .uri("/notifications/detail?notificationId=missing")
            .cookie(StubSecurityPlatform.COOKIE_NAME, "cookie-public")
            .exchange()
            .expectBody()
            .returnResult();

    assertNotEquals(401, result.getStatus().value());
    assertNotEquals(503, result.getStatus().value());
  }

  @Test
  void routesThatOnlyShareAPrefixWithAPublicOneStayProtected() {
    for (final String path :
        List.of(
            "/openapi/otro.yaml",
            "/openapi",
            "/v3/api-docs-x",
            "/v3/api-docs/otro",
            "/openapi/api-notificaciones.yaml/extra")) {
      client.get().uri(path).exchange().expectStatus().isUnauthorized();
    }
  }

  @Test
  void theSwaggerInterfaceIsNotPublicNorServedByDefault() {
    for (final String path : List.of("/swagger-ui.html", "/swagger-ui/index.html")) {
      client.get().uri(path).exchange().expectStatus().isUnauthorized();
    }
    final EntityExchangeResult<byte[]> withCredentials =
        client
            .get()
            .uri("/swagger-ui.html")
            .cookie(StubSecurityPlatform.COOKIE_NAME, "cookie-public")
            .exchange()
            .expectBody()
            .returnResult();
    assertEquals(404, withCredentials.getStatus().value());
  }

  @Test
  void aPublicRouteIsNotAWriteDoor() {
    client
        .post()
        .uri("/v3/api-docs")
        .exchange()
        .expectStatus()
        .value(status -> assertNotEquals(200, status.intValue()));
  }

  @Test
  void theManagementPortStillAnswersProbesAndMetricsWithoutCredentials() {
    for (final String path :
        List.of(
            "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus")) {
      management.get().uri(path).exchange().expectStatus().isOk();
    }
  }
}
