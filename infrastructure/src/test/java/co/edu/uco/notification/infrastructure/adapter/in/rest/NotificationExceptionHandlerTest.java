package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationId;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentNotReadyException;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentUploadExpiredException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.exception.InvalidStatusTransitionException;
import co.edu.uco.notification.core.exception.NotificationAlreadyAcceptedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.core.exception.ProviderNotAvailableException;
import co.edu.uco.notification.infrastructure.config.LogLines;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.FailureCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

class NotificationExceptionHandlerTest {

  private final NotificationExceptionHandler handler = new NotificationExceptionHandler();

  private ListAppender<ILoggingEvent> logs;
  private Logger handlerLogger;

  @BeforeEach
  void attachLogAppender() {
    logs = new ListAppender<>();
    logs.start();
    handlerLogger = (Logger) LoggerFactory.getLogger(NotificationExceptionHandler.class);
    handlerLogger.addAppender(logs);
  }

  @AfterEach
  void detachLogAppender() {
    handlerLogger.detachAppender(logs);
  }

  private static MockServerWebExchange exchange() {
    return MockServerWebExchange.from(MockServerHttpRequest.get("/notifications").build());
  }

  @Test
  void versionConflictIsAConflictWithAFixedMessage() {
    final NotificationId id = NotificationId.newId();

    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response =
        handler.handleVersionConflict(new NotificationVersionConflictException(id), exchange());

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals(
        "the notification was modified concurrently, retry the operation",
        response.getBody().message());
    assertFalse(response.getBody().message().contains(id.value()));
    assertNotNull(response.getBody().correlationId());
  }

  @Test
  void alreadyAcceptedIsAConflictWithAFixedMessageThatDoesNotEchoTheKey() {
    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response =
        handler.handleAlreadyAccepted(
            new NotificationAlreadyAcceptedException(
                TenantId.of("tenant-a"), ExternalId.of("order-secret-42")),
            exchange());

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals(
        "a notification with this externalId was already accepted", response.getBody().message());
    assertFalse(response.getBody().message().contains("tenant-a"));
    assertFalse(response.getBody().message().contains("order-secret-42"));
  }

  @Test
  void invalidStatusTransitionIsAConflictWithAFixedMessage() {
    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response =
        handler.handleInvalidStatusTransition(
            new InvalidStatusTransitionException(
                NotificationStatus.DELIVERED, NotificationStatus.PENDING),
            exchange());

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals(
        "the notification state does not allow this operation", response.getBody().message());
  }

  @Test
  void anUnexpectedExceptionIsAGenericInternalErrorWithoutTheInternalMessage() {
    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response =
        handler.handleUnexpected(
            new IllegalStateException("mongo password=hunter2 leaked"), exchange());

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("an unexpected error occurred", response.getBody().message());
    assertFalse(response.getBody().message().contains("hunter2"));
    assertNotNull(response.getBody().correlationId());
    assertFalse(response.getBody().correlationId().isBlank());
  }

  @Test
  void anUnexpectedExceptionIsLoggedAtErrorWithItsCauseAndACategory() {
    final IllegalStateException cause = new IllegalStateException("boom");

    handler.handleUnexpected(cause, exchange());

    assertEquals(1, logs.list.size());
    final ILoggingEvent event = logs.list.get(0);
    assertEquals(Level.ERROR, event.getLevel());
    assertEquals(cause.getMessage(), event.getThrowableProxy().getMessage());
    assertTrue(LogLines.render(event).contains("failureCategory=RECOVERABLE_INFRASTRUCTURE"));
  }

  @Test
  void aResponseStatusExceptionKeepsItsStatusAndIsNotTurnedIntoAnInternalError() {
    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response =
        handler.handleResponseStatus(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "decoding detail"), exchange());

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Bad Request", response.getBody().message());
    assertEquals(ErrorCode.INVALID_REQUEST.format(), response.getBody().code());
  }

  @Test
  void existingMappingsAreUnchanged() {
    final NotificationId id = NotificationId.newId();

    assertEquals(
        HttpStatus.NOT_FOUND,
        handler.handleNotFound(new NotificationNotFoundException(id), exchange()).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        handler
            .handleChannelNotAvailable(
                new ChannelNotAvailableException(ChannelType.of("SMS")), exchange())
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        handler
            .handleIllegalArgument(new IllegalArgumentException("bad"), exchange())
            .getStatusCode());
    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> tooLarge =
        handler.handleBodyTooLarge(new DataBufferLimitException("limit"), exchange());
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getStatusCode());
  }

  private static void assertCode(
      final ErrorCode expected,
      final HttpStatus status,
      final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response) {
    assertEquals(status, response.getStatusCode());
    assertEquals(expected.format(), response.getBody().code());
    assertNotNull(response.getBody().message());
    assertNotNull(response.getBody().correlationId());
  }

  @Test
  void everyMappedExceptionAnswersWithItsCatalogCode() {
    final NotificationId id = NotificationId.newId();
    final MockServerWebExchange exchange = exchange();

    assertCode(
        ErrorCode.NOTIFICATION_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        handler.handleNotFound(new NotificationNotFoundException(id), exchange));
    assertCode(
        ErrorCode.CHANNEL_NOT_AVAILABLE,
        HttpStatus.BAD_REQUEST,
        handler.handleChannelNotAvailable(
            new ChannelNotAvailableException(ChannelType.of("SMS")), exchange));
    assertCode(
        ErrorCode.INVALID_CONTENT,
        HttpStatus.BAD_REQUEST,
        handler.handleInvalidContent(
            new InvalidContentException(ChannelType.of("SMS"), "bad"), exchange));
    assertCode(
        ErrorCode.INVALID_ATTACHMENT,
        HttpStatus.BAD_REQUEST,
        handler.handleInvalidAttachment(new InvalidAttachmentException(1, "bad"), exchange));
    assertCode(
        ErrorCode.ATTACHMENT_NOT_READY,
        HttpStatus.CONFLICT,
        handler.handleAttachmentConflict(new AttachmentNotReadyException(1), exchange));
    assertCode(
        ErrorCode.ATTACHMENT_NOT_UPLOADED,
        HttpStatus.CONFLICT,
        handler.handleAttachmentConflict(new AttachmentNotUploadedException(), exchange));
    assertCode(
        ErrorCode.ATTACHMENT_UPLOAD_EXPIRED,
        HttpStatus.CONFLICT,
        handler.handleAttachmentConflict(new AttachmentUploadExpiredException(), exchange));
    assertCode(
        ErrorCode.ATTACHMENT_UPLOAD_NOT_FOUND,
        HttpStatus.NOT_FOUND,
        handler.handleUploadNotFound(new AttachmentUploadNotFoundException(), exchange));
    assertCode(
        ErrorCode.ATTACHMENT_INSPECTION_UNAVAILABLE,
        HttpStatus.SERVICE_UNAVAILABLE,
        handler.handleInspectionUnavailable(
            new AttachmentInspectionUnavailableException("u"), exchange));
    assertCode(
        ErrorCode.REQUEST_BODY_TOO_LARGE,
        HttpStatus.PAYLOAD_TOO_LARGE,
        handler.handleBodyTooLarge(new DataBufferLimitException("limit"), exchange));
    assertCode(
        ErrorCode.INVALID_REQUEST,
        HttpStatus.BAD_REQUEST,
        handler.handleIllegalArgument(new IllegalArgumentException("bad"), exchange));
    assertCode(
        ErrorCode.NOTIFICATION_VERSION_CONFLICT,
        HttpStatus.CONFLICT,
        handler.handleVersionConflict(new NotificationVersionConflictException(id), exchange));
    assertCode(
        ErrorCode.NOTIFICATION_ALREADY_ACCEPTED,
        HttpStatus.CONFLICT,
        handler.handleAlreadyAccepted(
            new NotificationAlreadyAcceptedException(
                TenantId.of("tenant-a"), ExternalId.of("order-1")),
            exchange));
    assertCode(
        ErrorCode.INVALID_STATUS_TRANSITION,
        HttpStatus.CONFLICT,
        handler.handleInvalidStatusTransition(
            new InvalidStatusTransitionException(
                NotificationStatus.DELIVERED, NotificationStatus.PENDING),
            exchange));
  }

  @Test
  void everyMappedResponseIsLoggedOnceWithItsCodeAndCategory() {
    handler.handleNotFound(new NotificationNotFoundException(NotificationId.newId()), exchange());

    assertEquals(1, logs.list.size());
    final String rendered = LogLines.render(logs.list.get(0));
    assertTrue(rendered.contains("errorCode=" + ErrorCode.NOTIFICATION_NOT_FOUND.format()));
    assertTrue(rendered.contains("failureCategory=" + ErrorCode.NOTIFICATION_NOT_FOUND.category()));
  }

  @Test
  void anUncataloguedExceptionAnswersWithTheGenericCodeOfItsCategoryAndNeverItsMessage() {
    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response =
        handler.handleUnexpected(
            new ProviderNotAvailableException(
                co.edu.uco.notification.core.domain.valueobject.ProviderId.of("secret-provider")),
            exchange());

    assertCode(ErrorCode.INFRASTRUCTURE_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, response);
    assertEquals("an unexpected error occurred", response.getBody().message());
    assertFalse(response.getBody().message().contains("secret-provider"));
    assertEquals(
        FailureCategory.RECOVERABLE_INFRASTRUCTURE, ErrorCode.INFRASTRUCTURE_ERROR.category());
    assertTrue(
        LogLines.render(logs.list.get(0))
            .contains("errorCode=" + ErrorCode.INFRASTRUCTURE_ERROR.format()));
  }

  @Test
  void aServerSideResponseStatusExceptionUsesTheGenericInfrastructureCode() {
    final ResponseEntity<NotificationExceptionHandler.ErrorResponse> response =
        handler.handleResponseStatus(
            new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream detail"), exchange());

    assertCode(ErrorCode.INFRASTRUCTURE_ERROR, HttpStatus.BAD_GATEWAY, response);
    assertFalse(response.getBody().message().contains("upstream detail"));
  }
}
