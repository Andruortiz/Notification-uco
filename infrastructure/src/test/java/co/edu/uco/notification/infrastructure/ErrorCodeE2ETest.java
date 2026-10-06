package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Role;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.FailureCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.channels.EMAIL.providers[0]=controllable",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000"
    })
@Import(ErrorCodeE2ETest.ControllableSenderConfig.class)
@Testcontainers
class ErrorCodeE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  static final AtomicReference<AttemptResult> RESULT =
      new AtomicReference<>(AttemptResult.ACCEPTED);

  @TestConfiguration
  static class ControllableSenderConfig {

    @Bean
    NotificationSenderPort controllableSender() {
      return new NotificationSenderPort() {
        @Override
        public Mono<AttemptResult> send(final Notification notification) {
          return Mono.just(RESULT.get());
        }

        @Override
        public ProviderId providerId() {
          return ProviderId.of("controllable");
        }

        @Override
        public Optional<String> disabledReason() {
          return Optional.empty();
        }

        @Override
        public boolean supportsAttachments() {
          return true;
        }
      };
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration LIMIT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

  @Autowired private ChannelCatalogPort channelCatalogPort;

  private WebTestClient webTestClient;
  private ListAppender<ILoggingEvent> logs;
  private Logger rootLogger;

  @BeforeEach
  void setUp() {
    RESULT.set(AttemptResult.ACCEPTED);
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(LIMIT)
            .build();
    awaitRoute();
    logs = new ListAppender<>();
    logs.start();
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
  }

  private void awaitRoute() {
    final Instant deadline = Instant.now().plus(LIMIT);
    while (Instant.now().isBefore(deadline)) {
      if (channelCatalogPort
          .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
    }
    throw new IllegalStateException("channel EMAIL never became active");
  }

  private static Map<String, Object> body(final String externalId, final String channelType) {
    return Map.of(
        "externalId", externalId,
        "channelType", channelType,
        "recipientId", "recipient-1",
        "recipientAddress", "alice@example.com",
        "subject", "Subject",
        "body", "Body",
        "priority", "NORMAL");
  }

  private static JsonNode errorBody(final EntityExchangeResult<byte[]> result) throws Exception {
    return MAPPER.readTree(result.getResponseBody());
  }

  private List<String> lines() {
    return logs.list.stream().map(LogLines::render).collect(Collectors.toList());
  }

  private void assertResponseAndLogAgree(
      final JsonNode response, final ErrorCode expected, final String correlationId) {
    final String code = response.get("code").asText();
    assertEquals(expected.format(), code);
    assertEquals(correlationId, response.get("correlationId").asText());
    assertTrue(Arrays.stream(ErrorCode.values()).anyMatch(entry -> entry.format().equals(code)));
    final List<String> matching =
        lines().stream()
            .filter(line -> line.contains("errorCode=" + code))
            .filter(line -> line.contains("correlationId=" + correlationId))
            .collect(Collectors.toList());
    assertFalse(matching.isEmpty(), "no log carries the response code");
    assertTrue(
        matching.stream()
            .anyMatch(line -> line.contains("failureCategory=" + expected.category())));
  }

  @Test
  void aValidationErrorCarriesTheSameCodeInTheResponseAndTheLog() throws Exception {
    final String correlationId = "error-code-validation-1";

    final EntityExchangeResult<byte[]> result =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer("tenant-a"))
            .header(CorrelationId.HEADER, correlationId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body("error-code-validation", "PIGEON"))
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody()
            .returnResult();

    assertResponseAndLogAgree(errorBody(result), ErrorCode.CHANNEL_NOT_AVAILABLE, correlationId);
  }

  @Test
  void anUnauthenticatedRequestCarriesTheSameCodeInTheResponseAndTheLog() throws Exception {
    final String correlationId = "error-code-401";

    final EntityExchangeResult<byte[]> result =
        webTestClient
            .get()
            .uri("/notifications")
            .header(CorrelationId.HEADER, correlationId)
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectBody()
            .returnResult();

    assertResponseAndLogAgree(errorBody(result), ErrorCode.AUTHENTICATION_REQUIRED, correlationId);
  }

  @Test
  void aRoleBelowTheMinimumCarriesTheSameCodeInTheResponseAndTheLog() throws Exception {
    final String correlationId = "error-code-403";

    final EntityExchangeResult<byte[]> result =
        webTestClient
            .get()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer("tenant-a", Role.CLIENTE))
            .header(CorrelationId.HEADER, correlationId)
            .exchange()
            .expectStatus()
            .isForbidden()
            .expectBody()
            .returnResult();

    assertResponseAndLogAgree(errorBody(result), ErrorCode.INSUFFICIENT_ROLE, correlationId);
  }

  @Test
  void aPermanentProviderRejectionIsLoggedWithItsCatalogCode() throws Exception {
    RESULT.set(AttemptResult.PERMANENT_FAILURE);

    final String notificationId = accept("error-code-permanent");

    awaitStatus(notificationId, "FAILED");
    assertTrue(
        lines().stream()
            .anyMatch(
                line ->
                    line.contains("errorCode=" + ErrorCode.NOTIFICATION_FAILED.format())
                        && line.contains("notificationId=" + notificationId)
                        && line.contains("failureCategory=" + FailureCategory.PERMANENT_BUSINESS)));
  }

  @Test
  void aRecoverableProviderFailureIsLoggedWithItsCatalogCode() throws Exception {
    RESULT.set(AttemptResult.RECOVERABLE_FAILURE);

    final String notificationId = accept("error-code-recoverable");

    awaitStatus(notificationId, "RECOVERABLE");
    assertTrue(
        lines().stream()
            .anyMatch(
                line ->
                    line.contains("errorCode=" + ErrorCode.PROVIDER_RECOVERABLE_FAILURE.format())
                        && line.contains("notificationId=" + notificationId)
                        && line.contains(
                            "failureCategory=" + FailureCategory.RECOVERABLE_PROVIDER)));
  }

  private String accept(final String externalId) throws Exception {
    final EntityExchangeResult<byte[]> result =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer("tenant-a"))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(externalId, "EMAIL"))
            .exchange()
            .expectStatus()
            .isEqualTo(202)
            .expectBody()
            .returnResult();
    final String id = MAPPER.readTree(result.getResponseBody()).get("notificationId").asText();
    assertNotNull(id);
    return id;
  }

  private void awaitStatus(final String notificationId, final String expected) throws Exception {
    final Instant deadline = Instant.now().plus(LIMIT);
    String status = null;
    while (Instant.now().isBefore(deadline)) {
      final EntityExchangeResult<byte[]> result =
          webTestClient
              .get()
              .uri("/notifications/" + notificationId)
              .header("Authorization", TestTokens.bearer("tenant-a"))
              .exchange()
              .expectBody()
              .returnResult();
      final JsonNode node = MAPPER.readTree(result.getResponseBody());
      status = node.has("status") ? node.get("status").asText() : null;
      if (expected.equals(status)) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("status never reached " + expected + ", last " + status);
  }
}
