package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.NotificationAlreadyAcceptedException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import co.edu.uco.notification.utils.CorrelationId;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class ErrorResponsesE2ETest {

  private static final String INTERNAL_MESSAGE = "mongo password=hunter2 leaked in a stack trace";

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  @LocalServerPort private int port;

  @MockBean private SendNotificationUseCase sendNotificationUseCase;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  private static Map<String, Object> body() {
    return Map.of(
        "externalId", "error-e2e-1",
        "channelType", "EMAIL",
        "recipientId", "recipient-1",
        "recipientAddress", "alice@example.com",
        "subject", "Subject",
        "body", "Body",
        "priority", "NORMAL");
  }

  private WebTestClient.ResponseSpec send(final String correlationId) {
    return webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-a"))
        .header(CorrelationId.HEADER, correlationId)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body())
        .exchange();
  }

  @Test
  void aCorrectRequestIsAcceptedAsAPositiveControl() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));

    send("err-e2e-ok").expectStatus().isEqualTo(202);
  }

  @Test
  void aVersionConflictRespondsConflictWithAFixedMessageAndTheCorrelationId() {
    final NotificationId id = NotificationId.newId();
    when(sendNotificationUseCase.send(any()))
        .thenReturn(Mono.error(new NotificationVersionConflictException(id)));

    send("err-e2e-409")
        .expectStatus()
        .isEqualTo(409)
        .expectHeader()
        .valueEquals(CorrelationId.HEADER, "err-e2e-409")
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("the notification was modified concurrently, retry the operation")
        .jsonPath("$.correlationId")
        .isEqualTo("err-e2e-409");
  }

  @Test
  void aDuplicateExternalIdRespondsConflict() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.error(
                new NotificationAlreadyAcceptedException(
                    TenantId.of("tenant-a"), ExternalId.of("error-e2e-1"))));

    send("err-e2e-dup")
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("a notification with this externalId was already accepted");
  }

  @Test
  void anUnexpectedErrorRespondsAGenericInternalErrorWithoutTheInternalMessage() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(Mono.error(new IllegalStateException(INTERNAL_MESSAGE)));

    final String responseBody =
        new String(
            send("err-e2e-500")
                .expectStatus()
                .isEqualTo(500)
                .expectHeader()
                .valueEquals(CorrelationId.HEADER, "err-e2e-500")
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("an unexpected error occurred")
                .jsonPath("$.correlationId")
                .isEqualTo("err-e2e-500")
                .returnResult()
                .getResponseBody());

    assertFalse(responseBody.contains("hunter2"));
    assertFalse(responseBody.contains(INTERNAL_MESSAGE));
  }
}
