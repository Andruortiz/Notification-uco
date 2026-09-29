package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.NotificationSearchPage;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.SearchNotificationsUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import java.nio.charset.StandardCharsets;
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

  private static final String ATTACHMENT_CONTENT = "c2VjcmV0LWNvbnRlbnQtMzE0MQ==";
  private static final String ATTACHMENT_URL =
      "http://minio.test/notification-attachments/tenants/tenant-1/uploads/u-1"
          + "?X-Amz-Signature=secret-token-3141";

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
            "sizeBytes": 20,
            "content": "%s"
          },
          {
            "fileName": "contract.pdf",
            "contentType": "application/pdf",
            "sizeBytes": 2000000,
            "url": "%s"
          }
        ]
      }
      """
          .formatted(ATTACHMENT_CONTENT, ATTACHMENT_URL);

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
  void sendMapsTheAttachmentsInOrderAsSubmissionsWithANormalizedType() {
    final SendNotificationCommand command = sentCommand(REQUEST_WITH_ATTACHMENTS);

    assertEquals(
        List.of(
            AttachmentSubmission.embedded(
                "invoice.pdf", "application/pdf", 20L, ATTACHMENT_CONTENT),
            AttachmentSubmission.reference(
                "contract.pdf", "application/pdf", 2_000_000L, ATTACHMENT_URL)),
        command.attachments());
    assertTrue(command.content().attachments().isEmpty());
    assertEquals("Subject", command.content().subject());
  }

  @Test
  void sendWithoutAttachmentsMapsAnEmptyList() {
    assertTrue(sentCommand(REQUEST_BODY).attachments().isEmpty());
  }

  @Test
  void sendWithNullAttachmentsMapsAnEmptyList() {
    final String body =
        REQUEST_BODY.replace(
            "\"priority\": \"NORMAL\"", "\"priority\": \"NORMAL\", \"attachments\": null");

    assertTrue(sentCommand(body).attachments().isEmpty());
  }

  @Test
  void attachmentRequestToStringHidesTheContentAndTheUrl() {
    final String text =
        new AttachmentRequest(
                "invoice.pdf", "application/pdf", 20L, ATTACHMENT_CONTENT, ATTACHMENT_URL)
            .toString();

    assertFalse(text.contains(ATTACHMENT_CONTENT), text);
    assertFalse(text.contains("secret-token-3141"), text);
    assertTrue(text.contains("invoice.pdf"), text);
  }

  @Test
  void sendReturnsBadRequestNamingTheAttachmentWhenAnAttachmentIsInvalid() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.error(
                new InvalidAttachmentException(
                    1, "sizeBytes must not exceed 10485760", "contract.pdf")));

    webTestClient
        .post()
        .uri("/notifications")
        .header("X-Tenant-Id", "tenant-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_WITH_ATTACHMENTS)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("attachments[1]: sizeBytes must not exceed 10485760 (contract.pdf)")
        .consumeWith(
            result -> {
              final String responseBody =
                  new String(result.getResponseBody(), StandardCharsets.UTF_8);
              assertFalse(responseBody.contains("secret-token-3141"), responseBody);
              assertFalse(responseBody.contains(ATTACHMENT_CONTENT), responseBody);
            });
  }
}
