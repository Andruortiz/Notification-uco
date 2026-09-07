package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.BatchId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemResult;
import co.edu.uco.notification.core.port.in.BatchNotificationItem;
import co.edu.uco.notification.core.port.in.SendNotificationBatchCommand;
import co.edu.uco.notification.core.port.in.SendNotificationBatchUseCase;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import co.edu.uco.notification.utils.Preconditions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class SendNotificationBatchService implements SendNotificationBatchUseCase {

  private static final int MAX_CONCURRENT_ITEMS = 16;

  private final SendNotificationUseCase sendNotificationUseCase;

  public SendNotificationBatchService(final SendNotificationUseCase sendNotificationUseCase) {
    this.sendNotificationUseCase =
        Preconditions.requireNonNull(
            sendNotificationUseCase, "sendNotificationUseCase must not be null");
  }

  @Override
  public Mono<BatchAcceptedResult> sendBatch(final SendNotificationBatchCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");

    final BatchId batchId = command.batchId() != null ? command.batchId() : BatchId.newId();

    return Flux.fromIterable(command.items())
        .flatMapSequential(item -> processItem(command, item), MAX_CONCURRENT_ITEMS)
        .collectList()
        .map(results -> new BatchAcceptedResult(batchId, results));
  }

  private Mono<BatchItemResult> processItem(
      final SendNotificationBatchCommand command, final BatchNotificationItem item) {
    final SendNotificationCommand itemCommand =
        new SendNotificationCommand(
            command.tenantId(),
            item.externalId(),
            item.channelType(),
            item.recipientId(),
            item.recipient(),
            item.content(),
            item.priority());

    return sendNotificationUseCase
        .send(itemCommand)
        .map(
            result ->
                result.duplicate()
                    ? BatchItemResult.duplicate(item.externalId(), result.notificationId())
                    : BatchItemResult.accepted(item.externalId(), result.notificationId()))
        .onErrorResume(
            ex ->
                ex instanceof ChannelNotAvailableException || ex instanceof InvalidContentException,
            ex -> Mono.just(BatchItemResult.rejected(item.externalId(), ex.getMessage())));
  }
}
