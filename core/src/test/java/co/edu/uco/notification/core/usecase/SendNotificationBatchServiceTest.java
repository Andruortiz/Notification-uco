package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.BatchId;
import co.edu.uco.notification.core.domain.ChannelType;
import co.edu.uco.notification.core.domain.ExternalId;
import co.edu.uco.notification.core.domain.NotificationContent;
import co.edu.uco.notification.core.domain.NotificationId;
import co.edu.uco.notification.core.domain.NotificationStatus;
import co.edu.uco.notification.core.domain.Priority;
import co.edu.uco.notification.core.domain.Recipient;
import co.edu.uco.notification.core.domain.RecipientId;
import co.edu.uco.notification.core.domain.TenantId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidContentException;
import co.edu.uco.notification.core.port.in.BatchAcceptedResult;
import co.edu.uco.notification.core.port.in.BatchItemOutcome;
import co.edu.uco.notification.core.port.in.BatchNotificationItem;
import co.edu.uco.notification.core.port.in.SendNotificationBatchCommand;
import co.edu.uco.notification.core.port.in.SendNotificationCommand;
import co.edu.uco.notification.core.port.in.SendNotificationResult;
import co.edu.uco.notification.core.port.in.SendNotificationUseCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class SendNotificationBatchServiceTest {

  private static final TenantId TENANT_ID = TenantId.of("tenant-1");

  private final SendNotificationUseCase sendNotificationUseCase =
      Mockito.mock(SendNotificationUseCase.class);

  private SendNotificationBatchService service;

  @BeforeEach
  void setUp() {
    service = new SendNotificationBatchService(sendNotificationUseCase);
  }

  private static BatchNotificationItem item(final String externalId) {
    return new BatchNotificationItem(
        ExternalId.of(externalId),
        ChannelType.of("EMAIL"),
        RecipientId.of("recipient-1"),
        Recipient.of("alice@example.com"),
        NotificationContent.of("Body"),
        Priority.NORMAL);
  }

  private static SendNotificationCommand toCommand(final BatchNotificationItem item) {
    return new SendNotificationCommand(
        TENANT_ID,
        item.externalId(),
        item.channelType(),
        item.recipientId(),
        item.recipient(),
        item.content(),
        item.priority());
  }

  @Test
  void sendBatchAggregatesAcceptedDuplicateAndRejectedOutcomes() {
    final BatchNotificationItem accepted = item("order-1");
    final BatchNotificationItem duplicate = item("order-2");
    final BatchNotificationItem rejected = item("order-3");

    final NotificationId acceptedId = NotificationId.newId();
    final NotificationId duplicateId = NotificationId.newId();

    when(sendNotificationUseCase.send(toCommand(accepted)))
        .thenReturn(
            Mono.just(new SendNotificationResult(acceptedId, NotificationStatus.PENDING, false)));
    when(sendNotificationUseCase.send(toCommand(duplicate)))
        .thenReturn(
            Mono.just(new SendNotificationResult(duplicateId, NotificationStatus.PENDING, true)));
    when(sendNotificationUseCase.send(toCommand(rejected)))
        .thenReturn(Mono.error(new ChannelNotAvailableException(rejected.channelType())));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(
            TENANT_ID, BatchId.of("batch-1"), List.of(accepted, duplicate, rejected));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertEquals(BatchId.of("batch-1"), result.batchId());
    assertEquals(3, result.results().size());

    assertEquals(BatchItemOutcome.ACCEPTED, result.results().get(0).outcome());
    assertEquals(acceptedId, result.results().get(0).notificationId());

    assertEquals(BatchItemOutcome.DUPLICATE, result.results().get(1).outcome());
    assertEquals(duplicateId, result.results().get(1).notificationId());

    assertEquals(BatchItemOutcome.REJECTED, result.results().get(2).outcome());
    assertEquals("Channel not available: EMAIL", result.results().get(2).rejectionReason());
  }

  @Test
  void sendBatchRejectsItemsWithInvalidContent() {
    final BatchNotificationItem rejected = item("order-1");
    when(sendNotificationUseCase.send(toCommand(rejected)))
        .thenReturn(
            Mono.error(new InvalidContentException(rejected.channelType(), "subject is required")));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(TENANT_ID, BatchId.of("batch-1"), List.of(rejected));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertEquals(BatchItemOutcome.REJECTED, result.results().get(0).outcome());
  }

  @Test
  void sendBatchGeneratesBatchIdWhenNoneGiven() {
    final BatchNotificationItem accepted = item("order-1");
    when(sendNotificationUseCase.send(toCommand(accepted)))
        .thenReturn(
            Mono.just(
                new SendNotificationResult(
                    NotificationId.newId(), NotificationStatus.PENDING, false)));

    final SendNotificationBatchCommand command =
        new SendNotificationBatchCommand(TENANT_ID, null, List.of(accepted));

    final BatchAcceptedResult result = service.sendBatch(command).block();

    assertNotNull(result);
    assertNotNull(result.batchId());
  }

  @Test
  void sendBatchRejectsNullCommand() {
    assertThrows(NullPointerException.class, () -> service.sendBatch(null));
  }

  @Test
  void constructorRejectsNullSendNotificationUseCase() {
    assertThrows(NullPointerException.class, () -> new SendNotificationBatchService(null));
  }
}
