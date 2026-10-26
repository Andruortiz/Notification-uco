package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.contract.ApiContract;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class RoleAuthorizationE2ETest {

  private static final String TENANT_A = "tenant-role-a";
  private static final String TENANT_B = "tenant-role-b";
  private static final String STREAM = "/notifications/stream";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  private WebTestClient web;

  @BeforeEach
  void setUp() {
    web =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
  }

  private static List<ApiContract.Operation> availableOperations() {
    return ApiContract.operations().stream()
        .filter(ApiContract.Operation::available)
        .filter(operation -> !STREAM.equals(operation.path()))
        .toList();
  }

  private WebTestClient.ResponseSpec send(
      final HttpMethod method, final String path, final String authorization, final Object body) {
    final WebTestClient.RequestBodySpec spec =
        web.method(method).uri(path).header(HttpHeaders.AUTHORIZATION, authorization);
    if (body == null) {
      return spec.exchange();
    }
    return spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
  }

  private WebTestClient.ResponseSpec sendOperation(
      final ApiContract.Operation operation, final String authorization) {
    final Object body = operation.method().equals("GET") ? null : Map.of();
    return send(HttpMethod.valueOf(operation.method()), operation.path(), authorization, body);
  }

  private static void assertDenied(final WebTestClient.ResponseSpec response) {
    response.expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("NTF-2002");
  }

  private static void assertNotDenied(
      final WebTestClient.ResponseSpec response, final String context) {
    final EntityExchangeResult<byte[]> result = response.expectBody().returnResult();
    final String body =
        new String(
            Objects.requireNonNullElse(result.getResponseBody(), new byte[0]),
            StandardCharsets.UTF_8);
    assertNotEquals(401, result.getStatus().value(), context);
    assertFalse(
        result.getStatus().value() == 403 && body.contains("\"NTF-2002\""), context + " " + body);
  }

  @Test
  void eachSingleRoleIsAllowedExactlyTheAvailableOperationsItAppearsIn() {
    for (final Role role : Role.values()) {
      final String authorization = TestTokens.bearer(TENANT_A, role);
      for (final ApiContract.Operation operation : availableOperations()) {
        final String context = role + " " + operation.key();
        final WebTestClient.ResponseSpec response = sendOperation(operation, authorization);
        if (operation.allowedRoles().contains(role.name())) {
          assertNotDenied(response, context);
        } else {
          assertDenied(response);
        }
      }
    }
  }

  @Test
  void theServedStreamAcceptsTheClientAndRejectsTheAuditor() {
    web.get()
        .uri(STREAM)
        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TENANT_A, Role.CLIENTE))
        .exchange()
        .expectStatus()
        .isOk();

    assertDenied(send(HttpMethod.GET, STREAM, TestTokens.bearer(TENANT_A, Role.AUDITOR), null));
  }

  @Test
  void aTokenWithTwoRolesGetsTheUnionOfBothSets() {
    final String authorization =
        TestTokens.bearerWithRoleClaim(
            TENANT_A, List.of(Role.EDITOR_PLANTILLAS.name(), Role.OPERADOR.name()));

    assertNotDenied(
        send(HttpMethod.POST, "/templates", authorization, template("union-key")),
        "POST /templates");
    assertNotDenied(
        send(HttpMethod.GET, "/notifications", authorization, null), "GET /notifications");
    assertDenied(send(HttpMethod.POST, "/channels", authorization, Map.of()));
  }

  @Test
  void unknownRolesAreRejectedWithTheClaimsCodeAsBefore() {
    for (final Object claim :
        List.of("SUPERADMIN", "ADMINISTRADOR", List.of("ROL_INVENTADO", "ADMINISTRADOR"))) {
      send(HttpMethod.GET, "/notifications", TestTokens.bearerWithRoleClaim(TENANT_A, claim), null)
          .expectStatus()
          .isUnauthorized()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("NTF-2004");
    }
  }

  @Test
  void anUndeclaredRouteRequiresOnlyThePlatformAdministrator() {
    assertNotDenied(
        send(
            HttpMethod.GET,
            "/undeclared-route",
            TestTokens.bearer(TENANT_A, Role.ADMINISTRADOR_PLATAFORMA),
            null),
        "undeclared as platform admin");
    for (final Role role : Role.values()) {
      if (role != Role.ADMINISTRADOR_PLATAFORMA) {
        assertDenied(
            send(HttpMethod.GET, "/undeclared-route", TestTokens.bearer(TENANT_A, role), null));
      }
    }
  }

  @Test
  void aRoleOfOneTenantDoesNotReachTheTemplatesOfAnother() {
    final Map<?, ?> created =
        web.post()
            .uri("/templates")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TENANT_A, Role.EDITOR_PLANTILLAS))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(template("isolationkey"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    assertNotNull(created);
    final String templateId = String.valueOf(created.get("templateId"));

    web.get()
        .uri("/templates/detail?templateId=" + templateId)
        .header(
            HttpHeaders.AUTHORIZATION, TestTokens.bearer(TENANT_A, Role.ADMINISTRADOR_PLATAFORMA))
        .exchange()
        .expectStatus()
        .isOk();

    final EntityExchangeResult<byte[]> foreign =
        web.get()
            .uri("/templates/detail?templateId=" + templateId)
            .header(
                HttpHeaders.AUTHORIZATION,
                TestTokens.bearer(TENANT_B, Role.ADMINISTRADOR_PLATAFORMA))
            .exchange()
            .expectBody()
            .returnResult();
    assertNotEquals(200, foreign.getStatus().value());
    assertFalse(
        new String(
                Objects.requireNonNullElse(foreign.getResponseBody(), new byte[0]),
                StandardCharsets.UTF_8)
            .contains(templateId));
  }

  private static Map<String, Object> template(final String key) {
    return Map.of(
        "key", key,
        "category", "ACADEMICO",
        "channel", "EMAIL",
        "language", "es",
        "subject", "Bienvenida {{nombre}}",
        "body", "Hola {{nombre}}, tu curso {{curso}}",
        "variables", List.of(Map.of("name", "curso", "required", false)));
  }
}
