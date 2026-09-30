package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentNotReadyException;
import co.edu.uco.notification.core.exception.AttachmentNotUploadedException;
import co.edu.uco.notification.core.exception.AttachmentUploadNotFoundException;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidAttachmentException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class NotificationExceptionHandler {

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(final NotificationNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(ChannelNotAvailableException.class)
  public ResponseEntity<ErrorResponse> handleChannelNotAvailable(
      final ChannelNotAvailableException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(InvalidContentException.class)
  public ResponseEntity<ErrorResponse> handleInvalidContent(final InvalidContentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(InvalidAttachmentException.class)
  public ResponseEntity<ErrorResponse> handleInvalidAttachment(final InvalidAttachmentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler({AttachmentNotReadyException.class, AttachmentNotUploadedException.class})
  public ResponseEntity<ErrorResponse> handleAttachmentConflict(final RuntimeException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(AttachmentUploadNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleUploadNotFound(
      final AttachmentUploadNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(AttachmentInspectionUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleInspectionUnavailable(
      final AttachmentInspectionUnavailableException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ErrorResponse(e.getMessage()));
  }

  @ExceptionHandler(DataBufferLimitException.class)
  public ResponseEntity<ErrorResponse> handleBodyTooLarge(final DataBufferLimitException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(new ErrorResponse("the request body exceeds the maximum allowed size"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(final IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
  }

  public record ErrorResponse(String message) {}
}
