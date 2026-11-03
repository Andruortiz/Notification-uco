package co.edu.uco.notification.infrastructure.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.infrastructure.support.ControllableProvider;
import co.edu.uco.notification.infrastructure.support.FailoverClient;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.catalog.channels.EMAIL.providers[0]=conf-email",
      "notification.catalog.channels.SMS.providers[0]=conf-sms",
      "notification.scheduler.requeue-interval-ms=600000"
    })
class ResponseSchemasConformanceE2ETest extends ApiV2E2ESupport {

  private static final String PHONE = "+573001234567";
  private static final HttpServer PROMETHEUS = startPrometheus();

  @TestConfiguration
  static class ConformanceProviders {

    @Bean
    ControllableProvider confEmail() {
      final ControllableProvider provider = new ControllableProvider("conf-email");
      provider.respondWith(AttemptResult.PERMANENT_FAILURE);
      return provider;
    }

    @Bean
    ControllableProvider confSms() {
      final ControllableProvider provider = new ControllableProvider("conf-sms");
      provider.respondWith(AttemptResult.ACCEPTED);
      return provider;
    }
  }

  @DynamicPropertySource
  static void prometheusUrl(final DynamicPropertyRegistry registry) {
    registry.add(
        "provider-health.prometheus-url",
        () -> "http://localhost:" + PROMETHEUS.getAddress().getPort());
  }

  @AfterAll
  static void stopPrometheus() {
    PROMETHEUS.stop(0);
  }

  private static HttpServer startPrometheus() {
    try {
      final long now = Instant.now().getEpochSecond();
      final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/api/v1/",
          exchange -> {
            final String path = exchange.getRequestURI().getPath();
            final String query = exchange.getRequestURI().getQuery();
            final String body;
            if (path.endsWith("query_range")) {
              body =
                  "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                      + "{\"metric\":{\"provider\":\"brevo\",\"channel\":\"EMAIL\","
                      + "\"result\":\"recoverable\"},\"values\":[["
                      + (now - 120)
                      + ",\"0\"],["
                      + (now - 60)
                      + ",\"1.5\"]]}]}}";
            } else if (query.contains("histogram_quantile")) {
              body =
                  "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                      + "{\"metric\":{\"provider\":\"brevo\"},\"value\":["
                      + now
                      + ",\"0.25\"]}]}}";
            } else {
              body =
                  "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                      + "{\"metric\":{\"provider\":\"brevo\",\"channel\":\"EMAIL\","
                      + "\"result\":\"delivered\"},\"value\":["
                      + now
                      + ",\"90\"]},"
                      + "{\"metric\":{\"provider\":\"brevo\",\"channel\":\"EMAIL\","
                      + "\"result\":\"failed\"},\"value\":["
                      + now
                      + ",\"10\"]}]}}";
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private final Set<String> covered = new TreeSet<>();
  private final List<String> violations = new ArrayList<>();

  private Object body(final WebTestClient.ResponseSpec response) {
    return response
        .expectBody(new ParameterizedTypeReference<Object>() {})
        .returnResult()
        .getResponseBody();
  }

  private void conforms(final String operation, final Object body, final String schema) {
    covered.add(operation);
    SchemaConformance.violations(body, schema)
        .forEach(violation -> violations.add(operation + " " + violation));
  }

  @Test
  void everyAvailableOperationAnswersWithTheShapeTheContractDeclares()
      throws IOException, InterruptedException {
    final String auth = TestTokens.bearer(TENANT_A);

    final Map<String, Object> request = notification("v2-conf-1", "recipient-conf");
    final Object sent =
        body(
            client
                .post()
                .uri("/notifications")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isAccepted());
    conforms("POST /notifications", sent, "SendNotificationResponse");
    final String id = (String) ((Map<?, ?>) sent).get("notificationId");

    final Object duplicate =
        body(
            client
                .post()
                .uri("/notifications")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isAccepted());
    conforms("POST /notifications", duplicate, "SendNotificationResponse");
    assertEquals(true, ((Map<?, ?>) duplicate).get("duplicate"));

    conforms(
        "GET /notifications/detail",
        body(detail(TENANT_A, id).expectStatus().isOk()),
        "NotificationStatusResponse");

    conforms(
        "GET /notifications",
        body(
            client
                .get()
                .uri("/notifications?recipientId=recipient-conf")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "NotificationSearchResponse");

    final Object batch =
        body(
            client
                .post()
                .uri("/notifications/batches")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    Map.of(
                        "items",
                        List.of(
                            notification("v2-conf-batch-1", "recipient-conf"),
                            Map.of(
                                "externalId", "v2-conf-batch-2",
                                "channelType", "NO_SUCH_CHANNEL",
                                "recipientId", "recipient-conf",
                                "recipientAddress", "alice@example.com",
                                "body", "Body",
                                "priority", "NORMAL"))))
                .exchange()
                .expectStatus()
                .isAccepted());
    conforms("POST /notifications/batches", batch, "BatchAcceptedResponse");
    final List<?> results = (List<?>) ((Map<?, ?>) batch).get("results");
    assertEquals(2, results.size());
    assertEquals("REJECTED", ((Map<?, ?>) results.get(1)).get("outcome"));
    assertEquals(null, ((Map<?, ?>) results.get(1)).get("notificationId"));

    conforms(
        "GET /channels",
        body(
            client
                .get()
                .uri("/channels")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "ChannelCatalogResponse");
    conforms(
        "GET /providers",
        body(
            client
                .get()
                .uri("/providers")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "ProviderCatalogResponse");
    conforms(
        "GET /configuration",
        body(
            client
                .get()
                .uri("/configuration")
                .header("Authorization", auth)
                .exchange()
                .expectStatus()
                .isOk()),
        "ConfigurationResponse");

    final var issuedResult =
        issueUpload(TENANT_A)
            .expectStatus()
            .isCreated()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult();
    final Map<String, Object> issued = issuedResult.getResponseBody();
    assertNotNull(issued);
    conforms("POST /attachment-uploads", issued, "AttachmentUploadResponse");
    final String uploadId = (String) issued.get("uploadId");
    conforms(
        "GET /attachment-uploads/status",
        body(
            client
                .get()
                .uri(
                    URI.create(
                        "http://localhost:"
                            + port
                            + issuedResult.getResponseHeaders().getFirst("Location")))
                .header("Authorization", TestTokens.bearer(TENANT_A, Role.CLIENTE))
                .exchange()
                .expectStatus()
                .isOk()),
        "AttachmentUploadResponse");
    assertEquals(204, uploadFile(issued, fileOfBytes(1_048_577)));
    conforms(
        "POST /attachment-upload-completions",
        body(
            client
                .post()
                .uri("/attachment-upload-completions")
                .header("Authorization", TestTokens.bearer(TENANT_A, Role.CLIENTE))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("uploadId", uploadId))
                .exchange()
                .expectStatus()
                .isAccepted()),
        "AttachmentUploadResponse");

    final Object notFound = body(detail(TENANT_B, id).expectStatus().isNotFound());
    assertEquals(List.of(), SchemaConformance.violations(notFound, "ErrorResponse"));

    exerciseNivelUnoOperations();

    assertEquals(List.of(), violations);
    final Set<String> available = new TreeSet<>();
    ApiContract.operations().stream()
        .filter(ApiContract.Operation::available)
        .forEach(operation -> available.add(operation.key()));
    available.remove("GET /notifications/stream");
    assertFalse(available.isEmpty());
    assertEquals(available, covered);
  }

  private WebTestClient.ResponseSpec call(
      final HttpMethod method, final String uri, final Role role, final Object payload) {
    final WebTestClient.RequestBodySpec request =
        client
            .method(method)
            .uri(uri)
            .header("Authorization", TestTokens.bearer(TENANT_A, role))
            .contentType(MediaType.APPLICATION_JSON);
    return (payload == null ? request : request.bodyValue(payload)).exchange();
  }

  private Map<?, ?> conformingMap(
      final String operation, final WebTestClient.ResponseSpec response, final String schema) {
    final Object result = body(response);
    conforms(operation, result, schema);
    return (Map<?, ?>) result;
  }

  private void exerciseNivelUnoOperations() {
    final FailoverClient notifications = new FailoverClient(port);
    final String failedId =
        notifications.accept(TENANT_A, "v2-conf-failed", "EMAIL", "recipient-failed");
    notifications.awaitStatus(TENANT_A, failedId, "FAILED");

    conformingMap(
        "GET /notifications/timeline",
        call(
                HttpMethod.GET,
                "/notifications/timeline?notificationId=" + failedId,
                Role.OPERADOR,
                null)
            .expectStatus()
            .isOk(),
        "NotificationTimelineResponse");
    conformingMap(
        "POST /notification-resends",
        call(
                HttpMethod.POST,
                "/notification-resends",
                Role.OPERADOR,
                Map.of("notificationId", failedId, "channelType", "SMS", "recipient", PHONE))
            .expectStatus()
            .isAccepted(),
        "NotificationStatusResponse");
    conformingMap(
        "POST /notification-retries",
        call(
                HttpMethod.POST,
                "/notification-retries",
                Role.OPERADOR,
                Map.of("notificationId", failedId))
            .expectStatus()
            .isAccepted(),
        "NotificationStatusResponse");
    conformingMap(
        "POST /provider-connection-tests",
        call(
                HttpMethod.POST,
                "/provider-connection-tests",
                Role.ADMINISTRADOR_PLATAFORMA,
                Map.of("channel", "EMAIL", "providerId", "conf-email"))
            .expectStatus()
            .isOk(),
        "ProviderConnectionTestResponse");
    conformingMap(
        "GET /provider-health",
        call(HttpMethod.GET, "/provider-health", Role.OPERADOR, null).expectStatus().isOk(),
        "ProviderHealthResponse");

    final Map<String, String> address = Map.of("channel", "EMAIL", "address", "alice@example.com");
    conformingMap(
        "POST /recipients/undeliverable-lookups",
        call(HttpMethod.POST, "/recipients/undeliverable-lookups", Role.OPERADOR, address)
            .expectStatus()
            .isOk(),
        "UndeliverableRecipientResponse");
    call(
            HttpMethod.POST,
            "/recipients/undeliverable-releases",
            Role.ADMINISTRADOR_PLATAFORMA,
            address)
        .expectStatus()
        .isNoContent();
    covered.add("POST /recipients/undeliverable-releases");

    final Map<?, ?> device =
        conformingMap(
            "POST /devices",
            call(
                    HttpMethod.POST,
                    "/devices",
                    Role.CLIENTE,
                    Map.of("platform", "ANDROID", "token", "conf-device-token-0123456789"))
                .expectStatus()
                .isCreated(),
            "DeviceResponse");
    conformingMap(
        "GET /devices",
        call(HttpMethod.GET, "/devices", Role.CLIENTE, null).expectStatus().isOk(),
        "DeviceListResponse");
    call(
            HttpMethod.POST,
            "/device-removals",
            Role.CLIENTE,
            Map.of("deviceId", device.get("deviceId")))
        .expectStatus()
        .isNoContent();
    covered.add("POST /device-removals");
    call(
            HttpMethod.POST,
            "/device-erasures",
            Role.ADMINISTRADOR_PLATAFORMA,
            Map.of("subject", "test-client"))
        .expectStatus()
        .isNoContent();
    covered.add("POST /device-erasures");

    final Map<?, ?> template =
        conformingMap(
            "POST /templates",
            call(
                    HttpMethod.POST,
                    "/templates",
                    Role.EDITOR_PLANTILLAS,
                    Map.of(
                        "key",
                        "conf-template",
                        "category",
                        "ACADEMICO",
                        "channel",
                        "EMAIL",
                        "language",
                        "es",
                        "subject",
                        "Hola {{nombre}}",
                        "body",
                        "Hola {{nombre}} {{curso}}",
                        "variables",
                        List.of(
                            Map.of("name", "nombre", "required", true),
                            Map.of("name", "curso", "required", false))))
                .expectStatus()
                .isCreated(),
            "TemplateResponse");
    final String templateId = (String) template.get("templateId");
    conformingMap(
        "PUT /templates",
        call(
                HttpMethod.PUT,
                "/templates",
                Role.EDITOR_PLANTILLAS,
                Map.of(
                    "templateId",
                    templateId,
                    "version",
                    template.get("version"),
                    "category",
                    "ACADEMICO",
                    "subject",
                    "Hola {{nombre}}",
                    "body",
                    "Hola {{nombre}} {{curso}}"))
            .expectStatus()
            .isOk(),
        "TemplateResponse");
    conformingMap(
        "GET /templates",
        call(HttpMethod.GET, "/templates?language=es", Role.CLIENTE, null).expectStatus().isOk(),
        "TemplateListResponse");
    conformingMap(
        "GET /templates/detail",
        call(HttpMethod.GET, "/templates/detail?templateId=" + templateId, Role.CLIENTE, null)
            .expectStatus()
            .isOk(),
        "TemplateResponse");
    conformingMap(
        "POST /template-variable-detections",
        call(
                HttpMethod.POST,
                "/template-variable-detections",
                Role.EDITOR_PLANTILLAS,
                Map.of("subject", "{{a}}", "body", "{{b}} {{a}}"))
            .expectStatus()
            .isOk(),
        "DetectedTemplateVariablesResponse");
    conformingMap(
        "POST /template-previews",
        call(
                HttpMethod.POST,
                "/template-previews",
                Role.EDITOR_PLANTILLAS,
                Map.of("channel", "EMAIL", "body", "Hola {{a}}", "values", Map.of("a", "Ana")))
            .expectStatus()
            .isOk(),
        "TemplatePreviewResponse");
    conformingMap(
        "POST /template-renders",
        call(
                HttpMethod.POST,
                "/template-renders",
                Role.CLIENTE,
                Map.of("templateId", templateId, "values", Map.of("nombre", "Ana", "curso", "X")))
            .expectStatus()
            .isOk(),
        "RenderedTemplateResponse");
    conformingMap(
        "POST /template-archivals",
        call(
                HttpMethod.POST,
                "/template-archivals",
                Role.EDITOR_PLANTILLAS,
                Map.of("templateId", templateId))
            .expectStatus()
            .isOk(),
        "TemplateResponse");
  }
}
