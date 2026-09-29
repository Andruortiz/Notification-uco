package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.Attachment;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.NotificationSearchPage;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.SearchNotificationsUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

  @MockBean private SearchNotificationsUseCase searchNotificationsUseCase;

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
  void sendReturnsBadRequestWhenContentDoesNotMatchTheChannelSchema() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.error(
                new InvalidContentException(ChannelType.of("EMAIL"), "subject is required")));

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

  @Test
  void searchReturnsAPageOfResults() {
    when(searchNotificationsUseCase.search(any()))
        .thenReturn(Mono.just(new NotificationSearchPage(List.of(), 50, 0, false)));

    webTestClient
        .get()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items")
        .isEmpty()
        .jsonPath("$.limit")
        .isEqualTo(50)
        .jsonPath("$.hasNext")
        .isEqualTo(false);
  }

  @Test
  void searchReturnsBadRequestForAnInvalidStatus() {
    webTestClient
        .get()
        .uri("/notifications?status=NOT_A_REAL_STATUS")
        .header("X-Tenant-Id", "tenant-1")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  private static final String ATTACHMENT_URL =
      "https://files.example.test/secret-token-3141/invoice.pdf";

  private static final String REQUEST_WITH_ATTACHMENTS =
      """
      {
        "externalId": "order-43",
        "channelType": "EMAIL",
        "recipientId": "recipient-1",
        "recipientAddress": "alice@example.com",
        "subject": "Subject",
        "body": "Body",
        "priority": "NORMAL",
        "attachments": [
          {
            "fileName": "invoice.pdf",
            "contentType": "Application/PDF",
            "sizeBytes": 1024,
            "url": "%s"
          },
          {
            "fileName": "receipt.png",
            "contentType": "image/png",
            "sizeBytes": 2048,
            "url": "https://files.example.test/receipt.png"
          }
        ]
      }
      """
          .formatted(ATTACHMENT_URL);

  private SendNotificationCommand sentCommand(final String body) {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));

    webTestClient
        .post()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED);

    final ArgumentCaptor<SendNotificationCommand> command =
        ArgumentCaptor.forClass(SendNotificationCommand.class);
    verify(sendNotificationUseCase).send(command.capture());
    return command.getValue();
  }

  @Test
  void sendMapsTheAttachmentsInOrderWithANormalizedType() {
    final SendNotificationCommand command = sentCommand(REQUEST_WITH_ATTACHMENTS);

    assertEquals(
        List.of(
            Attachment.of("invoice.pdf", "application/pdf", 1024L, ATTACHMENT_URL),
            Attachment.of(
                "receipt.png", "image/png", 2048L, "https://files.example.test/receipt.png")),
        command.content().attachments());
  }

  @Test
  void sendWithoutAttachmentsMapsAnEmptyList() {
    assertTrue(sentCommand(REQUEST_BODY).content().attachments().isEmpty());
  }

  @Test
  void sendWithNullAttachmentsMapsAnEmptyList() {
    final String body =
        REQUEST_BODY.replace(
            "\"priority\": \"NORMAL\"", "\"priority\": \"NORMAL\", \"attachments\": null");

    assertTrue(sentCommand(body).content().attachments().isEmpty());
  }
}
