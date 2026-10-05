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
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
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
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler(ChannelNotAvailableException.class)
  public ResponseEntity<ErrorResponse> handleChannelNotAvailable(
      final ChannelNotAvailableException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler(InvalidContentException.class)
  public ResponseEntity<ErrorResponse> handleInvalidContent(
      final InvalidContentException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler(InvalidAttachmentException.class)
  public ResponseEntity<ErrorResponse> handleInvalidAttachment(
      final InvalidAttachmentException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler({
    AttachmentNotReadyException.class,
    AttachmentNotUploadedException.class,
    AttachmentUploadExpiredException.class
  })
  public ResponseEntity<ErrorResponse> handleAttachmentConflict(
      final RuntimeException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler(AttachmentUploadNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleUploadNotFound(
      final AttachmentUploadNotFoundException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler(AttachmentInspectionUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleInspectionUnavailable(
      final AttachmentInspectionUnavailableException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler(DataBufferLimitException.class)
  public ResponseEntity<ErrorResponse> handleBodyTooLarge(
      final DataBufferLimitException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(
            new ErrorResponse(
                "the request body exceeds the maximum allowed size", correlationIdOf(exchange)));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(
      final IllegalArgumentException e, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse(e.getMessage(), correlationIdOf(exchange)));
  }

  @ExceptionHandler(NotificationVersionConflictException.class)
  public ResponseEntity<ErrorResponse> handleVersionConflict(
      final NotificationVersionConflictException e, final ServerWebExchange exchange) {
    return conflict("the notification was modified concurrently, retry the operation", exchange);
  }

  @ExceptionHandler(NotificationAlreadyAcceptedException.class)
  public ResponseEntity<ErrorResponse> handleAlreadyAccepted(
      final NotificationAlreadyAcceptedException e, final ServerWebExchange exchange) {
    return conflict("a notification with this externalId was already accepted", exchange);
  }

  @ExceptionHandler(InvalidStatusTransitionException.class)
  public ResponseEntity<ErrorResponse> handleInvalidStatusTransition(
      final InvalidStatusTransitionException e, final ServerWebExchange exchange) {
    return conflict("the notification state does not allow this operation", exchange);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(
      final ResponseStatusException e, final ServerWebExchange exchange) {
    final HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
    final String message = status != null ? status.getReasonPhrase() : "request failed";
    return ResponseEntity.status(e.getStatusCode())
        .headers(e.getHeaders())
        .body(new ErrorResponse(message, correlationIdOf(exchange)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(
      final Exception e, final ServerWebExchange exchange) {
    LOGGER.error(
        LogFields.fields(LogFields.FAILURE_CATEGORY, FailureCategory.RECOVERABLE_INFRASTRUCTURE),
        "Unexpected error while handling request",
        e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse("an unexpected error occurred", correlationIdOf(exchange)));
  }

  private static ResponseEntity<ErrorResponse> conflict(
      final String message, final ServerWebExchange exchange) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse(message, correlationIdOf(exchange)));
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

  public record ErrorResponse(String message, String correlationId) {}
}
