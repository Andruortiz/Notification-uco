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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
class TemplatePreviewE2ETest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String SENTINEL = "SENTINEL-PREVIEW-5c81d0e9";
  private static final String BODY = "Hola {{nombre}}, tu curso {{curso}}";

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
    tenantA = "preview-a-" + UUID.randomUUID();
    tenantB = "preview-b-" + UUID.randomUUID();
    awaitRoute("EMAIL");
    attachJsonAppender();
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(appender);
    appender.stop();
  }

  @Test
  void previewRendersTheDraftExactlyAsTheSavedTemplateRenders() {
    final Reply created =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("same", "EMAIL", BODY, null));
    final String id = created.body().get("templateId").asString();
    final Map<String, String> values = Map.of("nombre", "Ana", "curso", "Math");

    final Reply preview =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("channel", "EMAIL", "body", BODY, "values", values));
    final Reply render =
        call(
            HttpMethod.POST,
            "/template-renders",
            tenantA,
            Role.CLIENTE,
            Map.of("templateId", id, "values", values));

    assertEquals(200, preview.status());
    assertEquals("Hola Ana, tu curso Math", preview.body().get("body").asString());
    assertEquals(render.body().get("body").asString(), preview.body().get("body").asString());
    assertEquals(0, preview.body().get("missingVariables").size());
  }

  @Test
  void aMissingRequiredVariableRendersEmptyAndIsListed() {
    final Reply preview =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("channel", "EMAIL", "body", BODY, "values", Map.of("nombre", "Ana")));

    assertEquals(200, preview.status());
    assertEquals("Hola Ana, tu curso ", preview.body().get("body").asString());
    assertEquals("curso", preview.body().get("missingVariables").get(0).asString());
  }

  @Test
  void aValueContainingAPlaceholderIsInsertedLiterally() {
    final Reply preview =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "channel",
                "EMAIL",
                "body",
                "Hola {{nombre}}",
                "values",
                Map.of("nombre", "{{otra}}")));

    assertEquals("Hola {{otra}}", preview.body().get("body").asString());
  }

  @Test
  void invalidSyntaxAnswersWithItsOwnCode() {
    final Reply preview =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("channel", "EMAIL", "body", "Hola {{1abc}}", "values", Map.of()));

    assertEquals(400, preview.status());
    assertEquals("NTF-1007", preview.body().get("code").asString());
  }

  @Test
  void smsPreviewReportsEncodingUnitsAndSegments() {
    final Reply oneSegment =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "channel",
                "SMS",
                "body",
                "Hola {{nombre}} 😀 tu cita es el lunes",
                "values",
                Map.of("nombre", "Ana")));
    final Reply twoSegments =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "channel",
                "SMS",
                "body",
                "Ana, tu pedido está listo para retirar hoy en la sede norte del campus.",
                "values",
                Map.of()));
    final Reply email =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of("channel", "EMAIL", "body", BODY, "values", Map.of("nombre", "Ana")));

    assertEquals(30, oneSegment.body().get("characterCount").asInt());
    assertEquals("UCS2", oneSegment.body().get("sms").get("encoding").asString());
    assertEquals(31, oneSegment.body().get("sms").get("units").asInt());
    assertEquals(1, oneSegment.body().get("sms").get("segments").asInt());
    assertEquals(2, twoSegments.body().get("sms").get("segments").asInt());
    assertEquals(67, twoSegments.body().get("sms").get("unitsPerSegment").asInt());
    assertTrue(twoSegments.body().get("sms").get("exceedsSingleSegment").asBoolean());
    assertTrue(email.body().get("sms").isNull());
    assertEquals(19, email.body().get("characterCount").asInt());
  }

  @Test
  void sampleValuesAreSavedReadBackAndValidated() {
    final Reply created =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("samples", "EMAIL", BODY, Map.of("nombre", "Ana")));
    final String id = created.body().get("templateId").asString();
    final Reply detail =
        call(HttpMethod.GET, "/templates/detail?templateId=" + id, tenantA, Role.CLIENTE, null);

    assertEquals(201, created.status());
    assertEquals("Ana", detail.body().get("sampleValues").get("nombre").asString());

    final Reply undeclared =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("undeclared", "EMAIL", BODY, Map.of("otro", "x")));
    assertEquals(400, undeclared.status());
    assertEquals("NTF-1008", undeclared.body().get("code").asString());

    final Reply tooMany =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("too-many", "EMAIL", BODY, manyValues(51)));
    assertEquals(400, tooMany.status());
    assertEquals("NTF-1009", tooMany.body().get("code").asString());

    final Reply plain =
        call(
            HttpMethod.POST,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            template("plain", "EMAIL", BODY, null));
    assertEquals(0, plain.body().get("sampleValues").size());

    final Reply updated =
        call(
            HttpMethod.PUT,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "templateId",
                id,
                "version",
                0,
                "category",
                "ACADEMICO",
                "body",
                "Hola {{curso}}",
                "sampleValues",
                Map.of("curso", "Math")));
    assertEquals(200, updated.status());
    assertEquals("Math", updated.body().get("sampleValues").get("curso").asString());

    final Reply stale =
        call(
            HttpMethod.PUT,
            "/templates",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "templateId",
                id,
                "version",
                1,
                "category",
                "ACADEMICO",
                "body",
                "Hola {{curso}}",
                "sampleValues",
                Map.of("nombre", "Ana")));
    assertEquals(400, stale.status());
    assertEquals("NTF-1008", stale.body().get("code").asString());
  }

  @Test
  void sampleValuesNeverCrossBetweenTenants() {
    final String idA =
        call(
                HttpMethod.POST,
                "/templates",
                tenantA,
                Role.EDITOR_PLANTILLAS,
                template("shared", "EMAIL", BODY, Map.of("nombre", "Ana-A")))
            .body()
            .get("templateId")
            .asString();
    final String idB =
        call(
                HttpMethod.POST,
                "/templates",
                tenantB,
                Role.EDITOR_PLANTILLAS,
                template("shared", "EMAIL", BODY, Map.of("nombre", "Ana-B")))
            .body()
            .get("templateId")
            .asString();

    final Reply readA =
        call(
            HttpMethod.GET,
            "/templates/detail?templateId=" + idA,
            tenantA,
            Role.EDITOR_PLANTILLAS,
            null);
    final Reply readB =
        call(
            HttpMethod.GET,
            "/templates/detail?templateId=" + idB,
            tenantB,
            Role.EDITOR_PLANTILLAS,
            null);
    final Reply crossed =
        call(
            HttpMethod.GET,
            "/templates/detail?templateId=" + idA,
            tenantB,
            Role.EDITOR_PLANTILLAS,
            null);

    assertEquals("Ana-A", readA.body().get("sampleValues").get("nombre").asString());
    assertEquals("Ana-B", readB.body().get("sampleValues").get("nombre").asString());
    assertEquals(404, crossed.status());
  }

  @Test
  void aSentinelValueInAPreviewIsNeitherLoggedNorPersisted() {
    final String id =
        call(
                HttpMethod.POST,
                "/templates",
                tenantA,
                Role.EDITOR_PLANTILLAS,
                template("privacy", "EMAIL", BODY, null))
            .body()
            .get("templateId")
            .asString();

    final Reply preview =
        call(
            HttpMethod.POST,
            "/template-previews",
            tenantA,
            Role.EDITOR_PLANTILLAS,
            Map.of(
                "channel",
                "EMAIL",
                "body",
                BODY,
                "values",
                Map.of("nombre", SENTINEL, "curso", SENTINEL)));

    assertEquals(200, preview.status());
    assertTrue(preview.body().get("body").asString().contains(SENTINEL));
    final String logs = captured.toString(StandardCharsets.UTF_8);
    assertTrue(logs.contains(id), "control positivo: el canal de logs captura la creacion");
    assertFalse(logs.contains(SENTINEL));
    final String documents =
        mongoTemplate
            .getCollection("templates")
            .flatMapMany(collection -> Flux.from(collection.find()))
            .map(document -> document.toJson())
            .collectList()
            .block()
            .toString();
    assertTrue(documents.contains(id), "control positivo: se leyo la coleccion de plantillas");
    assertFalse(documents.contains(SENTINEL));
  }

  @Test
  void aMaximalPreviewAndAnInvalidOneEachAnswerWithinTwoHundredMilliseconds() {
    final List<String> names = IntStream.range(0, 50).mapToObj(i -> "v" + i).toList();
    final String maximal =
        names.stream().map(name -> "{{" + name + "}}").collect(Collectors.joining(" "))
            + " "
            + "x".repeat(19000);
    final Map<String, String> values =
        names.stream().collect(Collectors.toMap(name -> name, name -> "y".repeat(100)));
    final Map<String, Object> maximalRequest =
        Map.of("channel", "EMAIL", "body", maximal, "values", new HashMap<>(values));
    final Map<String, Object> invalidRequest =
        Map.of("channel", "EMAIL", "body", "{{" + " ".repeat(19990), "values", Map.of());
    call(HttpMethod.POST, "/template-previews", tenantA, Role.EDITOR_PLANTILLAS, maximalRequest);

    final Instant start = Instant.now();
    final Reply maximalReply =
        call(
            HttpMethod.POST, "/template-previews", tenantA, Role.EDITOR_PLANTILLAS, maximalRequest);
    final Duration maximalElapsed = Duration.between(start, Instant.now());

    final Instant invalidStart = Instant.now();
    final Reply invalidReply =
        call(
            HttpMethod.POST, "/template-previews", tenantA, Role.EDITOR_PLANTILLAS, invalidRequest);
    final Duration invalidElapsed = Duration.between(invalidStart, Instant.now());

    assertEquals(200, maximalReply.status());
    assertEquals(0, maximalReply.body().get("missingVariables").size());
    assertTrue(
        maximalElapsed.compareTo(Duration.ofMillis(200)) < 0,
        "vista previa maxima: " + maximalElapsed);
    assertEquals(400, invalidReply.status());
    assertEquals("NTF-1007", invalidReply.body().get("code").asString());
    assertTrue(
        invalidElapsed.compareTo(Duration.ofMillis(200)) < 0,
        "vista previa invalida: " + invalidElapsed);
  }

  private static Map<String, Object> template(
      final String key,
      final String channel,
      final String body,
      final Map<String, String> sampleValues) {
    final Map<String, Object> payload = new HashMap<>();
    payload.put("key", key);
    payload.put("category", "ACADEMICO");
    payload.put("channel", channel);
    payload.put("language", "es");
    payload.put("body", body);
    if (sampleValues != null) {
      payload.put("sampleValues", sampleValues);
    }
    return payload;
  }

  private static Map<String, String> manyValues(final int count) {
    return IntStream.range(0, count).boxed().collect(Collectors.toMap(i -> "v" + i, i -> "x"));
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
