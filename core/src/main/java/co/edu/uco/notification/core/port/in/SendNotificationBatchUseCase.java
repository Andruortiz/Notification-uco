package co.edu.uco.notification.core.port.in;

import reactor.core.publisher.Mono;

public interface SendNotificationBatchUseCase {

  Mono<BatchAcceptedResult> sendBatch(SendNotificationBatchCommand command);
}
