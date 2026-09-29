package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.SendNotificationBatchUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class NotificationBatchController {

  private final SendNotificationBatchUseCase sendNotificationBatchUseCase;

  public NotificationBatchController(
      final SendNotificationBatchUseCase sendNotificationBatchUseCase) {
    this.sendNotificationBatchUseCase = sendNotificationBatchUseCase;
  }

  @PostMapping("/notifications:sendBatch")
  public Mono<ResponseEntity<BatchAcceptedResponse>> sendBatch(
      @RequestHeader("X-Tenant-Id") final String tenantId,
      @RequestBody final SendNotificationBatchRequest request) {
    throw new UnsupportedOperationException();
  }
}
