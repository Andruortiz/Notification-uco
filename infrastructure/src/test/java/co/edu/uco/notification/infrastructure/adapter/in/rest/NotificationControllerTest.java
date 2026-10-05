package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentNotReadyException;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.port.in.AttachmentSummary;
import co.edu.uco.notification.core.port.in.GetNotificationStatusUseCase;
import co.edu.uco.notification.core.port.in.NotificationSearchPage;
import co.edu.uco.notification.core.port.in.NotificationStatusView;
import co.edu.uco.notification.core.port.in.SearchNotificationsUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.core.port.out.SubscriptionTicketPort;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticatedPrincipalArgumentResolver;
import co.edu.uco.notification.infrastructure.adapter.in.web.AuthenticationWebFilter;
import co.edu.uco.notification.infrastructure.adapter.in.web.RouteAuthorizationPolicy;
import co.edu.uco.notification.infrastructure.adapter.out.security.local.LocalJwtTokenValidationAdapter;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.infrastructure.config.SecurityConfig;
import co.edu.uco.notification.infrastructure.support.TestTokens;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.TraceParent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = NotificationController.class)
@Import({
  SecurityConfig.class,
  AuthenticationWebFilter.class,
  LocalJwtTokenValidationAdapter.class,
  RouteAuthorizationPolicy.class,
  AuthenticatedPrincipalArgumentResolver.class
})
class NotificationControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockBean private SubscriptionTicketPort subscriptionTicketPort;

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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void searchNamesTheAllowedValuesWhenTheStatusIsInvalid() {
    webTestClient
        .get()
        .uri("/notifications?status=NOT_A_REAL_STATUS")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .value(message -> assertTrue(message.toString().startsWith("status must be one of ")));
  }

  @Test
  void sendReturnsBadRequestWhenThePriorityIsMissing() {
    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_BODY.replace(",\n  \"priority\": \"NORMAL\"", ""))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("priority must not be blank");
  }

  @Test
  void sendNamesTheAllowedValuesWhenThePriorityIsUnknown() {
    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_BODY.replace("\"NORMAL\"", "\"normal\""))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("priority must be one of LOW, NORMAL, HIGH");
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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
        .header("Authorization", TestTokens.bearer("tenant-1"))
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

  private void assertSendFailsWith(
      final Throwable error, final HttpStatus status, final String message) {
    when(sendNotificationUseCase.send(any())).thenReturn(Mono.error(error));

    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_WITH_ATTACHMENTS)
        .exchange()
        .expectStatus()
        .isEqualTo(status)
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo(message);
  }

  @Test
  void anUploadStillBeingScannedIsAConflict() {
    assertSendFailsWith(
        new AttachmentNotReadyException(1),
        HttpStatus.CONFLICT,
        "attachments[1]: the upload is still being scanned; retry later");
  }

  @Test
  void anUnavailableInspectionIsServiceUnavailableWithoutInternalDetails() {
    assertSendFailsWith(
        new AttachmentInspectionUnavailableException(
            "the antivirus is not available", new IllegalStateException("clamd at 10.0.0.7")),
        HttpStatus.SERVICE_UNAVAILABLE,
        "the antivirus is not available");
  }

  @Test
  void anUnknownUploadIsNotFound() {
    assertSendFailsWith(
        new AttachmentUploadNotFoundException(),
        HttpStatus.NOT_FOUND,
        "attachment upload not found");
  }

  @Test
  void aFileNotYetUploadedIsAConflict() {
    assertSendFailsWith(
        new AttachmentNotUploadedException(),
        HttpStatus.CONFLICT,
        "the file has not been uploaded yet");
  }

  @Test
  void aBodyOverEightMegabytesIsPayloadTooLarge() {
    final String huge = "a".repeat(9 * 1024 * 1024);

    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_BODY.replace("\"Body\"", "\"" + huge + "\""))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  private ListAppender<ILoggingEvent> captureLogs() {
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(NotificationController.class)).addAppender(appender);
    return appender;
  }

  private static void release(final ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(NotificationController.class)).detachAppender(appender);
  }

  private static List<String> lines(final ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().map(LogLines::render).toList();
  }

  private void post(final String body, final HttpStatus expected) {
    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isEqualTo(expected);
  }

  @Test
  void anAcceptedNotificationWithAttachmentsIsLoggedWithMetadataAndHashOnly() {
    final NotificationId id = NotificationId.newId();
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    id,
                    NotificationStatus.PENDING,
                    false,
                    List.of(
                        new AttachmentSummary(
                            "invoice.pdf", "application/pdf", 20L, "a".repeat(64)),
                        new AttachmentSummary(
                            "contract.pdf", "application/pdf", 2_000_000L, "b".repeat(64))))));
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      post(REQUEST_WITH_ATTACHMENTS, HttpStatus.ACCEPTED);

      final String line =
          lines(logs).stream()
              .filter(message -> message.startsWith("Notification accepted with attachments"))
              .findFirst()
              .orElseThrow();
      assertTrue(line.contains("tenantId=tenant-1"), line);
      assertTrue(line.contains("externalId=order-43"), line);
      assertTrue(line.contains("notificationId=" + id.value()), line);
      assertTrue(line.contains("duplicate=false"), line);
      assertTrue(
          line.contains(
              "attachments=[invoice.pdf|application/pdf|20|"
                  + "a".repeat(64)
                  + ", contract.pdf|application/pdf|2000000|"
                  + "b".repeat(64)
                  + "]"),
          line);
      assertTrue(lines(logs).stream().noneMatch(message -> message.contains("secret-token-3141")));
      assertTrue(lines(logs).stream().noneMatch(message -> message.contains(ATTACHMENT_CONTENT)));
    } finally {
      release(logs);
    }
  }

  @Test
  void aRejectedNotificationWithAttachmentsIsLoggedWithMetadataAndReason() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.error(
                new InvalidAttachmentException(
                    0, "the file contains malicious software", "invoice.pdf")));
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      post(REQUEST_WITH_ATTACHMENTS, HttpStatus.BAD_REQUEST);

      final String line =
          lines(logs).stream()
              .filter(message -> message.startsWith("Notification with attachments rejected"))
              .findFirst()
              .orElseThrow();
      assertTrue(line.contains("tenantId=tenant-1"), line);
      assertTrue(line.contains("externalId=order-43"), line);
      assertTrue(
          line.contains(
              "attachments=[invoice.pdf|application/pdf|20, contract.pdf|application/pdf|2000000]"),
          line);
      assertTrue(
          line.contains("reason=attachments[0]: the file contains malicious software"), line);
      assertTrue(lines(logs).stream().noneMatch(message -> message.contains("secret-token-3141")));
      assertTrue(lines(logs).stream().noneMatch(message -> message.contains(ATTACHMENT_CONTENT)));
    } finally {
      release(logs);
    }
  }

  @Test
  void aFileNameWithLineBreaksIsLoggedOnASingleLine() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.error(
                new InvalidAttachmentException(
                    0, "fileName must not contain path separators or control characters")));
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      post(
          REQUEST_WITH_ATTACHMENTS.replace("invoice.pdf", "evil\\r\\nFAKE LOG LINE"),
          HttpStatus.BAD_REQUEST);

      final String line =
          lines(logs).stream()
              .filter(message -> message.startsWith("Notification with attachments rejected"))
              .findFirst()
              .orElseThrow();
      assertFalse(line.contains("\n"), line);
      assertFalse(line.contains("\r"), line);
      assertTrue(line.contains("evil__FAKE LOG LINE"), line);
    } finally {
      release(logs);
    }
  }

  @Test
  void aNotificationWithoutAttachmentsLogsNothingNew() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));
    final ListAppender<ILoggingEvent> logs = captureLogs();
    try {
      post(REQUEST_BODY, HttpStatus.ACCEPTED);

      assertTrue(lines(logs).isEmpty(), lines(logs).toString());
    } finally {
      release(logs);
    }
  }

  @Test
  void sendPassesTheRequestCorrelationIdToTheCommandAndEchoesItInTheResponse() {
    final NotificationId id = NotificationId.newId();
    when(sendNotificationUseCase.send(any()))
        .thenReturn(Mono.just(new SendNotificationResult(id, NotificationStatus.PENDING, false)));

    webTestClient
        .post()
        .uri("/notifications")
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .header(CorrelationId.HEADER, "req-send-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(REQUEST_BODY)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED)
        .expectHeader()
        .valueEquals(CorrelationId.HEADER, "req-send-1");

    final ArgumentCaptor<SendNotificationCommand> command =
        ArgumentCaptor.forClass(SendNotificationCommand.class);
    verify(sendNotificationUseCase).send(command.capture());
    assertEquals(CorrelationId.of("req-send-1"), command.getValue().correlationId());
  }

  @Test
  void sendWithoutTheHeaderGeneratesAnIdUsedInTheCommandAndTheResponse() {
    when(sendNotificationUseCase.send(any()))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));

    final String echoed =
        webTestClient
            .post()
            .uri("/notifications")
            .header("Authorization", TestTokens.bearer("tenant-1"))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(REQUEST_BODY)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.ACCEPTED)
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst(CorrelationId.HEADER);

    final ArgumentCaptor<SendNotificationCommand> command =
        ArgumentCaptor.forClass(SendNotificationCommand.class);
    verify(sendNotificationUseCase).send(command.capture());
    assertNotNull(echoed);
    assertEquals(echoed, command.getValue().correlationId().value());
  }

  @Test
  void getStatusExposesTheCorrelationId() {
    final NotificationId id = NotificationId.newId();
    when(getNotificationStatusUseCase.getStatus(any()))
        .thenReturn(
            Mono.just(
                new NotificationStatusView(
                    id,
                    NotificationStatus.PENDING,
                    ChannelType.of("EMAIL"),
                    null,
                    Instant.now(),
                    CorrelationId.of("corr-status"))));

    webTestClient
        .get()
        .uri("/notifications/{id}", id.value())
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.correlationId")
        .isEqualTo("corr-status");
  }

  @Test
  void errorResponsesCarryTheCorrelationIdInHeaderAndBody() {
    final NotificationId id = NotificationId.newId();
    when(getNotificationStatusUseCase.getStatus(any()))
        .thenReturn(Mono.error(new NotificationNotFoundException(id)));

    webTestClient
        .get()
        .uri("/notifications/{id}", id.value())
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .header(CorrelationId.HEADER, "req-404")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .valueEquals(CorrelationId.HEADER, "req-404")
        .expectBody()
        .jsonPath("$.correlationId")
        .isEqualTo("req-404");
  }

  @Test
  void anInvalidIncomingCorrelationIdIsReplacedAndNeverEchoed() {
    final NotificationId id = NotificationId.newId();
    when(getNotificationStatusUseCase.getStatus(any()))
        .thenReturn(Mono.error(new NotificationNotFoundException(id)));

    final String echoed =
        webTestClient
            .get()
            .uri("/notifications/{id}", id.value())
            .header("Authorization", TestTokens.bearer("tenant-1"))
            .header(CorrelationId.HEADER, "bad id with spaces")
            .exchange()
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst(CorrelationId.HEADER);

    assertNotNull(echoed);
    assertFalse(echoed.contains(" "));
  }

  @Test
  void aValidTraceparentIsEchoedInTheResponse() {
    final NotificationId id = NotificationId.newId();
    when(getNotificationStatusUseCase.getStatus(any()))
        .thenReturn(Mono.error(new NotificationNotFoundException(id)));
    final String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    webTestClient
        .get()
        .uri("/notifications/{id}", id.value())
        .header("Authorization", TestTokens.bearer("tenant-1"))
        .header(TraceParent.HEADER, traceparent)
        .exchange()
        .expectHeader()
        .valueEquals(TraceParent.HEADER, traceparent);
  }
}
