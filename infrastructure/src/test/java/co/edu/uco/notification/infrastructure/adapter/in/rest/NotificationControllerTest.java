package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.NotificationStatus;
import co.edu.uco.notification.core.domain.ProviderId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = NotificationController.class)
class NotificationControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockBean private SendNotificationUseCase sendNotificationUseCase;

  @MockBean private GetNotificationStatusUseCase getNotificationStatusUseCase;

  private static final String REQUEST_BODY =
      """
      {
        "externalId": "order-42",
        "channelType": "EMAIL",
        "recipientId": "recipient-1",
        "recipientAddress": "alice@example.com",
        "subject": "Subject",
        "body": "Body",
        "priority": "NORMAL"
      }
      """;

  @Test
  void sendReturnsAcceptedWithTheResult() {
    final NotificationId id = NotificationId.newId();
    when(sendNotificationUseCase.send(any()))
        .thenReturn(Mono.just(new SendNotificationResult(id, NotificationStatus.PENDING, false)));

    webTestClient
        .post()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_BODY)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED)
        .expectBody()
        .jsonPath("$.notificationId")
        .isEqualTo(id.value())
        .jsonPath("$.status")
        .isEqualTo("PENDING")
        .jsonPath("$.duplicate")
        .isEqualTo(false);
  }

  @Test
  void sendReturnsBadRequestWhenChannelIsNotAvailable() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(Mono.error(new ChannelNotAvailableException(ChannelType.of("SMS"))));

    webTestClient
        .post()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_BODY)
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void getStatusReturnsTheCurrentStatus() {
    final NotificationId id = NotificationId.newId();
    when(getNotificationStatusUseCase.getStatus(any()))
        .thenReturn(
            Mono.just(
                new NotificationStatusView(
                    id,
                    NotificationStatus.DELIVERED,
                    ChannelType.of("EMAIL"),
                    ProviderId.of("simulated"),
                    Instant.now())));

    webTestClient
        .get()
        .uri("/notifications/{id}", id.value())
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("DELIVERED")
        .jsonPath("$.providerId")
        .isEqualTo("simulated");
  }

  @Test
  void getStatusReturnsNotFoundWhenMissing() {
    final NotificationId id = NotificationId.newId();
    when(getNotificationStatusUseCase.getStatus(any()))
        .thenReturn(Mono.error(new NotificationNotFoundException(id)));

    webTestClient
        .get()
        .uri("/notifications/{id}", id.value())
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isNotFound();
  }
}
