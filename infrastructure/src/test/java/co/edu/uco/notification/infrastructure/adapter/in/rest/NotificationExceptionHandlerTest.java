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
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidStatusTransitionException;
import co.edu.uco.notification.core.exception.NotificationAlreadyAcceptedException;
import co.edu.uco.notification.core.exception.NotificationNotFoundException;
import co.edu.uco.notification.core.exception.NotificationVersionConflictException;
import co.edu.uco.notification.infrastructure.config.LogLines;
import java.util.List;
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
    assertTrue(logs.list.isEmpty());
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
    assertEquals(List.of(), logs.list);
  }
}
