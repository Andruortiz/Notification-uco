package co.edu.uco.notification.infrastructure.adapter.in.rest;

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
import co.edu.uco.notification.infrastructure.adapter.in.web.CorrelationIdWebFilter;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
public class NotificationExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationExceptionHandler.class);

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(
      final NotificationNotFoundException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.NOT_FOUND, ErrorCode.NOTIFICATION_NOT_FOUND, e.getMessage(), exchange);
  }

  @ExceptionHandler(ChannelNotAvailableException.class)
  public ResponseEntity<ErrorResponse> handleChannelNotAvailable(
      final ChannelNotAvailableException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.BAD_REQUEST, ErrorCode.CHANNEL_NOT_AVAILABLE, e.getMessage(), exchange);
  }

  @ExceptionHandler(InvalidContentException.class)
  public ResponseEntity<ErrorResponse> handleInvalidContent(
      final InvalidContentException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CONTENT, e.getMessage(), exchange);
  }

  @ExceptionHandler(InvalidAttachmentException.class)
  public ResponseEntity<ErrorResponse> handleInvalidAttachment(
      final InvalidAttachmentException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ATTACHMENT, e.getMessage(), exchange);
  }

  @ExceptionHandler({
    AttachmentNotReadyException.class,
    AttachmentNotUploadedException.class,
    AttachmentUploadExpiredException.class
  })
  public ResponseEntity<ErrorResponse> handleAttachmentConflict(
      final RuntimeException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.CONFLICT, conflictCodeOf(e), e.getMessage(), exchange);
  }

  @ExceptionHandler(AttachmentUploadNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleUploadNotFound(
      final AttachmentUploadNotFoundException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.NOT_FOUND, ErrorCode.ATTACHMENT_UPLOAD_NOT_FOUND, e.getMessage(), exchange);
  }

  @ExceptionHandler(AttachmentInspectionUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleInspectionUnavailable(
      final AttachmentInspectionUnavailableException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.SERVICE_UNAVAILABLE,
        ErrorCode.ATTACHMENT_INSPECTION_UNAVAILABLE,
        e.getMessage(),
        exchange);
  }

  @ExceptionHandler(DataBufferLimitException.class)
  public ResponseEntity<ErrorResponse> handleBodyTooLarge(
      final DataBufferLimitException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.PAYLOAD_TOO_LARGE,
        ErrorCode.REQUEST_BODY_TOO_LARGE,
        "the request body exceeds the maximum allowed size",
        exchange);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(
      final IllegalArgumentException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, e.getMessage(), exchange);
  }

  @ExceptionHandler(NotificationVersionConflictException.class)
  public ResponseEntity<ErrorResponse> handleVersionConflict(
      final NotificationVersionConflictException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.CONFLICT,
        ErrorCode.NOTIFICATION_VERSION_CONFLICT,
        "the notification was modified concurrently, retry the operation",
        exchange);
  }

  @ExceptionHandler(NotificationAlreadyAcceptedException.class)
  public ResponseEntity<ErrorResponse> handleAlreadyAccepted(
      final NotificationAlreadyAcceptedException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.CONFLICT,
        ErrorCode.NOTIFICATION_ALREADY_ACCEPTED,
        "a notification with this externalId was already accepted",
        exchange);
  }

  @ExceptionHandler(InvalidStatusTransitionException.class)
  public ResponseEntity<ErrorResponse> handleInvalidStatusTransition(
      final InvalidStatusTransitionException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.CONFLICT,
        ErrorCode.INVALID_STATUS_TRANSITION,
        "the notification state does not allow this operation",
        exchange);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(
      final ResponseStatusException e, final ServerWebExchange exchange) {
    final HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
    final String message = status != null ? status.getReasonPhrase() : "request failed";
    final ErrorCode code =
        e.getStatusCode().is5xxServerError()
            ? ErrorCode.genericFor(FailureCategory.RECOVERABLE_INFRASTRUCTURE)
            : ErrorCode.INVALID_REQUEST;
    return respond(e.getStatusCode(), code, message, exchange, e.getHeaders());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(
      final Exception e, final ServerWebExchange exchange) {
    final ErrorCode code = ErrorCode.genericFor(FailureCategory.RECOVERABLE_INFRASTRUCTURE);
    try (LogContext ignored = LogContext.open(correlationIdValueOf(exchange), null, null)) {
      LOGGER.error(LogFields.failure(code), "Unexpected error while handling request", e);
    }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            new ErrorResponse(
                code.format(), "an unexpected error occurred", correlationIdOf(exchange)));
  }

  private static ErrorCode conflictCodeOf(final RuntimeException e) {
    if (e instanceof AttachmentNotReadyException) {
      return ErrorCode.ATTACHMENT_NOT_READY;
    }
    if (e instanceof AttachmentNotUploadedException) {
      return ErrorCode.ATTACHMENT_NOT_UPLOADED;
    }
    return ErrorCode.ATTACHMENT_UPLOAD_EXPIRED;
  }

  private static ResponseEntity<ErrorResponse> respond(
      final HttpStatusCode status,
      final ErrorCode code,
      final String message,
      final ServerWebExchange exchange) {
    return respond(status, code, message, exchange, HttpHeaders.EMPTY);
  }

  private static ResponseEntity<ErrorResponse> respond(
      final HttpStatusCode status,
      final ErrorCode code,
      final String message,
      final ServerWebExchange exchange,
      final HttpHeaders headers) {
    final String correlationId = correlationIdOf(exchange);
    try (LogContext ignored =
        LogContext.open(CorrelationId.fromOrNull(correlationId), null, null)) {
      if (status.is5xxServerError()) {
        LOGGER.warn(LogFields.failure(code, "status", status.value()), "Request failed");
      } else {
        LOGGER.info(LogFields.failure(code, "status", status.value()), "Request failed");
      }
    }
    return ResponseEntity.status(status)
        .headers(headers)
        .body(new ErrorResponse(code.format(), message, correlationId));
  }

  private static CorrelationId correlationIdValueOf(final ServerWebExchange exchange) {
    return CorrelationId.fromOrNull(correlationIdOf(exchange));
  }

  private static String correlationIdOf(final ServerWebExchange exchange) {
    final Object attribute = exchange.getAttribute(CorrelationIdWebFilter.CORRELATION_ATTRIBUTE);
    if (attribute instanceof CorrelationId correlationId) {
      return correlationId.value();
    }
    final CorrelationId fromHeader =
        CorrelationId.fromOrNull(
            exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER));
    return fromHeader != null ? fromHeader.value() : CorrelationId.newId().value();
  }

  public record ErrorResponse(String code, String message, String correlationId) {}
}
