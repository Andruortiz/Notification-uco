package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentNotReadyException;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentUploadExpiredException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.infrastructure.adapter.in.web.CorrelationIdWebFilter;
import co.edu.uco.notification.utils.CorrelationId;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
public class NotificationExceptionHandler {

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
