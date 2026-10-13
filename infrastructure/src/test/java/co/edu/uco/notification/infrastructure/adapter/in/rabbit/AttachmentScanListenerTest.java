package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.out.AttachmentScanRequestPort;
import co.edu.uco.notification.infrastructure.config.AttachmentScanTopologyProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

@SpringBootTest(
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.attachments.scan.max-attempts=2"
    })
@Testcontainers
class AttachmentScanListenerTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

  @MockBean private ScanAttachmentUploadUseCase scanUseCase;

  @Autowired private AttachmentScanRequestPort scanRequestPort;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private AmqpAdmin amqpAdmin;

  @Autowired private AttachmentScanTopologyProperties topology;

  @BeforeEach
  void setUp() {
    reset(scanUseCase);
    amqpAdmin.purgeQueue(topology.queue(), false);
    amqpAdmin.purgeQueue(topology.dlqQueue(), false);
  }

  private static AttachmentUpload clean(final UploadId uploadId) {
    return AttachmentUpload.issue(
            uploadId,
            TENANT,
            "contract.pdf",
            "application/pdf",
            2_000_000L,
            NOW,
            NOW.plusSeconds(900))
        .markClean(Sha256Digest.of("x".getBytes(StandardCharsets.UTF_8)), NOW);
  }

  private long messagesIn(final String queue) {
    return amqpAdmin.getQueueInfo(queue).getMessageCount();
  }

  @Test
  void aScanRequestProducedByTheRealPublisherReachesTheUseCaseAndIsAcknowledged() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(TENANT, uploadId)).thenReturn(Mono.just(clean(uploadId)));

    scanRequestPort.requestScan(TENANT, uploadId).block();

    verify(scanUseCase, timeout(10_000)).scan(TENANT, uploadId);
    final Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline) && messagesIn(topology.queue()) > 0) {
      Mono.delay(Duration.ofMillis(100)).block();
    }
    assertEquals(0, messagesIn(topology.queue()));
    assertEquals(0, messagesIn(topology.dlqQueue()));
  }

  @Test
  void aRequestForAnAlreadyResolvedUploadIsAcknowledgedWithoutEffect() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(TENANT, uploadId)).thenReturn(Mono.empty());

    scanRequestPort.requestScan(TENANT, uploadId).block();

    verify(scanUseCase, timeout(10_000)).scan(TENANT, uploadId);
    Mono.delay(Duration.ofSeconds(1)).block();
    assertEquals(0, messagesIn(topology.dlqQueue()));
  }

  @Test
  void theMessageIsOnlyAcknowledgedAfterTheScanFinishes() {
    final UploadId uploadId = UploadId.newId();
    final AtomicInteger unackedWhileScanning = new AtomicInteger(-1);
    when(scanUseCase.scan(TENANT, uploadId))
        .thenReturn(
            Mono.delay(Duration.ofSeconds(2))
                .doOnNext(
                    tick ->
                        unackedWhileScanning.set(
                            amqpAdmin.getQueueInfo(topology.queue()).getMessageCount() + unacked()))
                .map(tick -> clean(uploadId)));

    scanRequestPort.requestScan(TENANT, uploadId).block();

    verify(scanUseCase, timeout(10_000)).scan(TENANT, uploadId);
    final Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline) && unackedWhileScanning.get() < 0) {
      Mono.delay(Duration.ofMillis(100)).block();
    }
    assertEquals(1, unackedWhileScanning.get());
  }

  private int unacked() {
    try {
      final String output =
          RABBIT
              .execInContainer("rabbitmqctl", "list_queues", "name", "messages_unacknowledged")
              .getStdout();
      return output
          .lines()
          .map(line -> line.trim().split("[ \\t]+"))
          .filter(columns -> columns.length == 2 && columns[0].equals(topology.queue()))
          .mapToInt(columns -> Integer.parseInt(columns[1]))
          .findFirst()
          .orElse(-100);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aScanThatKeepsFailingEndsInTheDeadLetterQueueWithItsPayload() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(any(), any()))
        .thenReturn(Mono.error(new AttachmentInspectionUnavailableException("clamav down")));

    scanRequestPort.requestScan(TENANT, uploadId).block();

    final Message dead = rabbitTemplate.receive(topology.dlqQueue(), 20_000);
    assertNotNull(dead);
    final String body = new String(dead.getBody(), StandardCharsets.UTF_8);
    assertTrue(body.contains(uploadId.value()), body);
    assertTrue(
        String.valueOf(dead.getMessageProperties().getHeaders().get("x-exception-message"))
            .contains("clamav down"));
    verify(scanUseCase, times(2)).scan(TENANT, uploadId);
    assertEquals(0, messagesIn(topology.queue()));
    assertEquals(0, messagesIn(topology.dlqQueue()));
  }

  @Test
  void anUnreadableMessageGoesStraightToTheDeadLetterQueueWithoutAnyScanAttempt() {
    rabbitTemplate.send(
        topology.exchange(),
        topology.routingKey(),
        new Message("{not json".getBytes(StandardCharsets.UTF_8)));

    final Message dead = rabbitTemplate.receive(topology.dlqQueue(), 20_000);

    assertNotNull(dead);
    assertEquals("{not json", new String(dead.getBody(), StandardCharsets.UTF_8));
    verify(scanUseCase, never()).scan(any(), any());
    assertEquals(0, messagesIn(topology.queue()));
    assertNull(dead.getMessageProperties().getHeaders().get("x-scan-attempt"));
  }

  @Test
  void aMessageWithoutIdentifiersGoesStraightToTheDeadLetterQueue() {
    rabbitTemplate.send(
        topology.exchange(),
        topology.routingKey(),
        new Message("{\"tenantId\":null,\"uploadId\":null}".getBytes(StandardCharsets.UTF_8)));

    assertNotNull(rabbitTemplate.receive(topology.dlqQueue(), 20_000));
    verify(scanUseCase, never()).scan(any(), any());
  }

  @Test
  void anObjectThatChangedWhileScanningIsRetriedAndScannedAgain() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(TENANT, uploadId))
        .thenReturn(Mono.error(new AttachmentObjectChangedException()))
        .thenReturn(Mono.just(clean(uploadId)));

    scanRequestPort.requestScan(TENANT, uploadId).block();

    verify(scanUseCase, timeout(10_000).times(2)).scan(TENANT, uploadId);
    Mono.delay(Duration.ofSeconds(1)).block();
    assertEquals(0, messagesIn(topology.queue()));
    assertEquals(0, messagesIn(topology.dlqQueue()));
  }
}
