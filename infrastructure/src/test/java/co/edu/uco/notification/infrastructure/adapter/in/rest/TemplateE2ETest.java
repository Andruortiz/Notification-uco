package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500"
    })
@Testcontainers
class TemplateE2ETest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String SENTINEL = "SENTINEL-VALUE-7f3a91c2";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private WebTestClient webTestClient;
  private String tenantA;
  private String tenantB;
  private ByteArrayOutputStream captured;
  private OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

  private record Reply(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    tenantA = "tenant-a-" + UUID.randomUUID();
    tenantB = "tenant-b-" + UUID.randomUUID();
    awaitRoute("EMAIL");
    attachJsonAppender();
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(appender);
    appender.stop();
  }

  @Test
  void fullLifecycleWithVariablesOptionalsConflictsAndArchive() {
    final Reply created =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            newTemplate("welcome", "es"));
    assertEquals(201, created.status());
    final String id = created.body().get("templateId").asString();
    assertEquals(0L, created.body().get("version").asLong());
    assertEquals("ACADEMICO", created.body().get("category").asString());
    assertFalse(created.body().get("critical").asBoolean());
    assertEquals(2, created.body().get("variables").size());
    assertTrue(created.body().get("variables").get(0).get("required").asBoolean());
    assertFalse(created.body().get("variables").get(1).get("required").asBoolean());
    assertEquals("curso", created.body().get("variables").get(1).get("name").asString());

    final Reply duplicate =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            newTemplate("welcome", "es"));
    assertEquals(409, duplicate.status());
    assertEquals("NTF-3051", duplicate.body().get("code").asString());

    final Reply detail =
        call(HttpMethod.GET, "/templates/detail?templateId=" + id, tenantA, Role.CLIENTE, null);
    assertEquals(200, detail.status());
    assertEquals("welcome", detail.body().get("key").asString());

    final Reply english =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            newTemplate("welcome", "en"));
    assertEquals(201, english.status());
    final Reply onlyEnglish =
        call(HttpMethod.GET, "/templates?language=en", tenantA, Role.CLIENTE, null);
    assertEquals(1, onlyEnglish.body().get("items").size());
    assertEquals(
        2,
        call(HttpMethod.GET, "/templates?size=20", tenantA, Role.CLIENTE, null)
            .body()
            .get("items")
            .size());

    final Reply missing =
        call(
            HttpMethod.POST,
            "/template-renders",
            tenantA,
            Role.CLIENTE,
            Map.of("templateId", id, "values", Map.of()));
    assertEquals(422, missing.status());
    assertEquals("NTF-1010", missing.body().get("code").asString());
    assertEquals(List.of("nombre"), strings(missing.body().get("missingVariables")));

    final Reply rendered =
        call(
            HttpMethod.POST,
            "/template-renders",
            tenantA,
            Role.CLIENTE,
            Map.of("templateId", id, "values", Map.of("nombre", "Ana {{curso}}", "otra", "x")));
    assertEquals(200, rendered.status());
    assertEquals("Hola Ana {{curso}}, tu curso ", rendered.body().get("body").asString());
    assertEquals("Bienvenida Ana {{curso}}", rendered.body().get("subject").asString());

    final Reply updated =
        call(
            HttpMethod.PUT,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "templateId", id,
                "version", 0,
                "category", "SEGURIDAD",
                "subject", "Nuevo {{nombre}}",
                "body", "Codigo {{codigo}} para {{nombre}}"));
    assertEquals(200, updated.status());
    assertEquals(1L, updated.body().get("version").asLong());
    assertTrue(updated.body().get("critical").asBoolean());
    assertEquals(List.of("nombre", "codigo"), names(updated.body().get("variables")));

    final Reply stale =
        call(
            HttpMethod.PUT,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("templateId", id, "version", 0, "category", "SEGURIDAD", "body", "x"));
    assertEquals(409, stale.status());
    assertEquals("NTF-3052", stale.body().get("code").asString());

    final Reply archived =
        call(
            HttpMethod.POST,
            "/template-archivals",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("templateId", id));
    assertEquals(200, archived.status());
    assertEquals("ARCHIVADA", archived.body().get("status").asString());

    final Reply renderArchived =
        call(
            HttpMethod.POST,
            "/template-renders",
            tenantA,
            Role.CLIENTE,
            Map.of("templateId", id, "values", Map.of("nombre", "a", "codigo", "b")));
    assertEquals(409, renderArchived.status());
    assertEquals("NTF-3053", renderArchived.body().get("code").asString());
    final Reply updateArchived =
        call(
            HttpMethod.PUT,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("templateId", id, "version", 2, "category", "SEGURIDAD", "body", "x"));
    assertEquals("NTF-3053", updateArchived.body().get("code").asString());
    final Reply archivedListing =
        call(HttpMethod.GET, "/templates?status=ARCHIVADA", tenantA, Role.CLIENTE, null);
    assertEquals(1, archivedListing.body().get("items").size());
    assertEquals("ARCHIVADA", archivedListing.body().get("items").get(0).get("status").asString());
  }

  @Test
  void validationFailuresUseTheirOwnCodes() {
    final Reply syntax =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("a", "es", "EMAIL", "Hola {{1abc}}"));
    assertEquals(400, syntax.status());
    assertEquals("NTF-1007", syntax.body().get("code").asString());

    final Reply spaces =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("a", "es", "EMAIL", "Hola {{      nombre}}"));
    assertEquals("NTF-1007", spaces.body().get("code").asString());

    final Reply undeclared =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "key", "a",
                "category", "ACADEMICO",
                "channel", "EMAIL",
                "language", "es",
                "body", "Hola {{nombre}}",
                "variables", List.of(Map.of("name", "sobrante", "required", true))));
    assertEquals(400, undeclared.status());
    assertEquals("NTF-1008", undeclared.body().get("code").asString());
    assertTrue(undeclared.body().get("message").asString().contains("sobrante"));

    final Reply tooLong =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("a", "es", "EMAIL", "x".repeat(20001)));
    assertEquals("NTF-1009", tooLong.body().get("code").asString());
    assertEquals(
        "NTF-1009",
        call(HttpMethod.GET, "/templates?size=101", tenantA, Role.CLIENTE, null)
            .body()
            .get("code")
            .asString());

    final Reply channel =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("a", "es", "CARRIER_PIGEON", "Hola"));
    assertEquals(400, channel.status());
    assertEquals("NTF-1003", channel.body().get("code").asString());

    final Reply detection =
        call(
            HttpMethod.POST,
            "/template-variable-detections",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("subject", "{{a}}", "body", "{{b}} {{ a }}"));
    assertEquals(200, detection.status());
    assertEquals(List.of("a", "b"), strings(detection.body().get("variables")));
  }

  @Test
  void rolesSeparateWritingFromReading() {
    final Reply clienteWrites =
        call(HttpMethod.POST, "/templates", tenantA, Role.CLIENTE, newTemplate("r", "es"));
    assertEquals(403, clienteWrites.status());
    assertEquals(
        403,
        call(
                HttpMethod.POST,
                "/template-archivals",
                tenantA,
                Role.CLIENTE,
                Map.of("templateId", "x"))
            .status());
    assertEquals(
        403,
        call(
                HttpMethod.POST,
                "/template-variable-detections",
                tenantA,
                Role.CLIENTE,
                Map.of("body", "x"))
            .status());
    assertEquals(200, call(HttpMethod.GET, "/templates", tenantA, Role.CLIENTE, null).status());
    assertEquals(
        201,
        call(HttpMethod.POST, "/templates", tenantA, Role.EDITOR_PLANTILLAS, newTemplate("r", "es"))
            .status());
  }

  @Test
  void aTenantNeverSeesEditsOrRendersTheTemplatesOfAnother() {
    final String id =
        call(
                HttpMethod.POST,
                "/templates",
                tenantA,
                Role.EDITOR_PLANTILLAS,
                newTemplate("shared", "es"))
            .body()
            .get("templateId")
            .asString();
    final Map<String, Object> render = Map.of("templateId", id, "values", Map.of("nombre", "Ana"));

    assertEquals(
        200,
        call(HttpMethod.POST, "/template-renders", tenantA, Role.EDITOR_PLANTILLAS, render)
            .status());
    assertEquals(
        1,
        call(HttpMethod.GET, "/templates", tenantA, Role.EDITOR_PLANTILLAS, null)
            .body()
            .get("items")
            .size());
    assertEquals(
        200,
        call(
                HttpMethod.GET,
                "/templates/detail?templateId=" + id,
                tenantA,
                Role.EDITOR_PLANTILLAS,
                null)
            .status());

    assertEquals(
        0,
        call(HttpMethod.GET, "/templates", tenantB, Role.ADMINISTRADOR_PLATAFORMA, null)
            .body()
            .get("items")
            .size());
    final Reply foreignDetail =
        call(
            HttpMethod.GET,
            "/templates/detail?templateId=" + id,
            tenantB,
            Role.ADMINISTRADOR_PLATAFORMA,
            null);
    assertEquals(404, foreignDetail.status());
    assertEquals("NTF-3050", foreignDetail.body().get("code").asString());
    assertEquals(
        404,
        call(HttpMethod.POST, "/template-renders", tenantB, Role.ADMINISTRADOR_PLATAFORMA, render)
            .status());
    assertEquals(
        404,
        call(
                HttpMethod.POST,
                "/template-archivals",
                tenantB,
                Role.ADMINISTRADOR_PLATAFORMA,
                Map.of("templateId", id))
            .status());
    assertEquals(
        404,
        call(
                HttpMethod.PUT,
                "/templates",
                tenantB,
                Role.ADMINISTRADOR_PLATAFORMA,
                Map.of("templateId", id, "version", 0, "category", "MARKETING", "body", "hackeado"))
            .status());

    assertEquals(
        201,
        call(
                HttpMethod.POST,
                "/templates",
                tenantB,
                Role.EDITOR_PLANTILLAS,
                newTemplate("shared", "es"))
            .status());
    final Reply untouched =
        call(
            HttpMethod.GET,
            "/templates/detail?templateId=" + id,
            tenantA,
            Role.EDITOR_PLANTILLAS,
            null);
    assertEquals(0L, untouched.body().get("version").asLong());
    assertEquals("ACTIVA", untouched.body().get("status").asString());
    assertTrue(untouched.body().get("body").asString().startsWith("Hola"));
  }

  @Test
  void renderedValuesAreNeitherLoggedNorPersisted() {
    final String id =
        call(
                HttpMethod.POST,
                "/templates",
                tenantA,
                Role.EDITOR_PLANTILLAS,
                newTemplate("privacy", "es"))
            .body()
            .get("templateId")
            .asString();

    final Reply ok =
        call(
            HttpMethod.POST,
            "/template-renders",
            tenantA,
            Role.CLIENTE,
            Map.of("templateId", id, "values", Map.of("nombre", SENTINEL, "curso", SENTINEL)));
    final Reply failing =
        call(
            HttpMethod.POST,
            "/template-renders",
            tenantA,
            Role.CLIENTE,
            Map.of("templateId", id, "values", Map.of("curso", SENTINEL)));

    assertEquals(200, ok.status());
    assertTrue(ok.body().get("body").asString().contains(SENTINEL));
    assertEquals(422, failing.status());
    final String logs = captured.toString(StandardCharsets.UTF_8);
    assertTrue(logs.contains(id), "control positivo: el canal de logs captura el recorrido");
    assertFalse(logs.contains(SENTINEL));
    final String documents =
        mongoTemplate
            .getCollection("templates")
            .flatMapMany(collection -> Flux.from(collection.find()))
            .map(document -> document.toJson())
            .collectList()
            .block()
            .toString();
    assertTrue(documents.contains(id), "control positivo: se leyo el documento de la plantilla");
    assertFalse(documents.contains(SENTINEL));
  }

  @Test
  void invalidListingFiltersAnswerWithTheValidationCode() {
    final Reply category =
        call(HttpMethod.GET, "/templates?category=NOPE", tenantA, Role.CLIENTE, null);
    final Reply status =
        call(HttpMethod.GET, "/templates?status=NOPE", tenantA, Role.CLIENTE, null);

    assertEquals(400, category.status());
    assertEquals("NTF-1004", category.body().get("code").asString());
    assertEquals(400, status.status());
    assertEquals("NTF-1004", status.body().get("code").asString());
    assertEquals(
        200,
        call(HttpMethod.GET, "/templates?category=ACADEMICO", tenantA, Role.CLIENTE, null)
            .status());
  }

  @Test
  void languageCaseVariantsAreTheSameTemplateAndTheFilterIgnoresCase() {
    assertEquals(
        201,
        call(
                HttpMethod.POST,
                "/templates",
                tenantA,
                Role.EDITOR_PLANTILLAS,
                newTemplate("lang", "ES"))
            .status());
    final Reply duplicate =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            newTemplate("lang", "es"));

    assertEquals(409, duplicate.status());
    assertEquals("NTF-3051", duplicate.body().get("code").asString());
    final Reply listed =
        call(HttpMethod.GET, "/templates?language=Es", tenantA, Role.CLIENTE, null);
    assertEquals(1, listed.body().get("items").size());
    assertEquals("es", listed.body().get("items").get(0).get("language").asString());
  }

  @Test
  void renderWithTooManyValuesIsRejectedWithTheLimitCode() {
    final String id =
        call(
                HttpMethod.POST,
                "/templates",
                tenantA,
                Role.EDITOR_PLANTILLAS,
                newTemplate("limits", "es"))
            .body()
            .get("templateId")
            .asString();
    final java.util.Map<String, String> values = new java.util.HashMap<>();
    for (int i = 0; i < 51; i++) {
      values.put("v" + i, "x");
    }

    final Reply reply =
        call(
            HttpMethod.POST,
            "/template-renders",
            tenantA,
            Role.CLIENTE,
            Map.of("templateId", id, "values", values));

    assertEquals(400, reply.status());
    assertEquals("NTF-1009", reply.body().get("code").asString());
  }

  private static Map<String, Object> newTemplate(final String key, final String language) {
    return Map.of(
        "key", key,
        "category", "ACADEMICO",
        "channel", "EMAIL",
        "language", language,
        "subject", "Bienvenida {{nombre}}",
        "body", "Hola {{nombre}}, tu curso {{curso}}",
        "variables", List.of(Map.of("name", "curso", "required", false)));
  }

  private static Map<String, Object> template(
      final String key, final String language, final String channel, final String body) {
    return Map.of(
        "key",
        key,
        "category",
        "ACADEMICO",
        "channel",
        channel,
        "language",
        language,
        "body",
        body);
  }

  private Reply call(
      final HttpMethod method,
      final String uri,
      final String tenant,
      final Role role,
      final Object body) {
    final WebTestClient.RequestBodySpec spec =
        webTestClient
            .method(method)
            .uri(uri)
            .header("Authorization", TestTokens.bearer(tenant, role));
    final WebTestClient.ResponseSpec response =
        body == null ? spec.exchange() : spec.bodyValue(body).exchange();
    final var result = response.expectBody().returnResult();
    final byte[] content = result.getResponseBody();
    final JsonNode json =
        content == null || content.length == 0
            ? MAPPER.createObjectNode()
            : MAPPER.readTree(content);
    return new Reply(result.getStatus().value(), json);
  }

  private static List<String> strings(final JsonNode array) {
    return array.valueStream().map(JsonNode::asString).toList();
  }

  private static List<String> names(final JsonNode variables) {
    return variables.valueStream().map(node -> node.get("name").asString()).toList();
  }

  private void awaitRoute(final String channelType) {
    final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      if (channelCatalogPort
          .findActiveRoute(ChannelType.of(channelType), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
    }
    throw new IllegalStateException("channel " + channelType + " never became active");
  }

  private static Logger rootLogger() {
    return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private void attachJsonAppender() {
    final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    captured = new ByteArrayOutputStream();
    final LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.addIncludeMdcKeyName("tenantId");
    encoder.start();
    appender = new OutputStreamAppender<>();
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.setOutputStream(captured);
    appender.start();
    rootLogger().addAppender(appender);
  }
}
