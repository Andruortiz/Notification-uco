package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.exception.InvalidTemplateSyntaxException;
import co.edu.uco.notification.core.exception.MissingTemplateVariablesException;
import co.edu.uco.notification.core.exception.TemplateAlreadyExistsException;
import co.edu.uco.notification.core.exception.TemplateArchivedException;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.exception.TemplateNotFoundException;
import co.edu.uco.notification.core.exception.TemplateVersionConflictException;
import co.edu.uco.notification.core.exception.UndeclaredTemplateVariableException;
import co.edu.uco.notification.infrastructure.adapter.in.web.CorrelationIdWebFilter;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {TemplateController.class, TemplatePreviewController.class})
public class TemplateExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(TemplateExceptionHandler.class);

  @ExceptionHandler(InvalidTemplateSyntaxException.class)
  public ResponseEntity<TemplateErrorResponse> handleSyntax(
      final InvalidTemplateSyntaxException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TEMPLATE_SYNTAX, e, null, exchange);
  }

  @ExceptionHandler(UndeclaredTemplateVariableException.class)
  public ResponseEntity<TemplateErrorResponse> handleUndeclared(
      final UndeclaredTemplateVariableException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.BAD_REQUEST, ErrorCode.UNDECLARED_TEMPLATE_VARIABLE, e, null, exchange);
  }

  @ExceptionHandler(TemplateLimitExceededException.class)
  public ResponseEntity<TemplateErrorResponse> handleLimit(
      final TemplateLimitExceededException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.TEMPLATE_LIMIT_EXCEEDED, e, null, exchange);
  }

  @ExceptionHandler(TemplateNotFoundException.class)
  public ResponseEntity<TemplateErrorResponse> handleNotFound(
      final TemplateNotFoundException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.NOT_FOUND, ErrorCode.TEMPLATE_NOT_FOUND, e, null, exchange);
  }

  @ExceptionHandler(TemplateAlreadyExistsException.class)
  public ResponseEntity<TemplateErrorResponse> handleAlreadyExists(
      final TemplateAlreadyExistsException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.CONFLICT, ErrorCode.TEMPLATE_ALREADY_EXISTS, e, null, exchange);
  }

  @ExceptionHandler(TemplateVersionConflictException.class)
  public ResponseEntity<TemplateErrorResponse> handleVersionConflict(
      final TemplateVersionConflictException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.CONFLICT, ErrorCode.TEMPLATE_VERSION_CONFLICT, e, null, exchange);
  }

  @ExceptionHandler(TemplateArchivedException.class)
  public ResponseEntity<TemplateErrorResponse> handleArchived(
      final TemplateArchivedException e, final ServerWebExchange exchange) {
    return respond(HttpStatus.CONFLICT, ErrorCode.TEMPLATE_ARCHIVED, e, null, exchange);
  }

  @ExceptionHandler(MissingTemplateVariablesException.class)
  public ResponseEntity<TemplateErrorResponse> handleMissing(
      final MissingTemplateVariablesException e, final ServerWebExchange exchange) {
    return respond(
        HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.MISSING_VARIABLES, e, e.missing(), exchange);
  }

  private static ResponseEntity<TemplateErrorResponse> respond(
      final HttpStatus status,
      final ErrorCode code,
      final RuntimeException e,
      final List<String> missing,
      final ServerWebExchange exchange) {
    final String correlationId = correlationIdOf(exchange);
    try (LogContext ignored =
        LogContext.open(CorrelationId.fromOrNull(correlationId), null, null)) {
      LOGGER.info(LogFields.failure(code, "status", status.value()), "Request failed");
    }
    return ResponseEntity.status(status)
        .body(new TemplateErrorResponse(code.format(), e.getMessage(), correlationId, missing));
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

  public record TemplateErrorResponse(
      String code,
      String message,
      String correlationId,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<String> missingVariables) {

    public TemplateErrorResponse {
      missingVariables = missingVariables == null ? null : List.copyOf(missingVariables);
    }
  }
}
