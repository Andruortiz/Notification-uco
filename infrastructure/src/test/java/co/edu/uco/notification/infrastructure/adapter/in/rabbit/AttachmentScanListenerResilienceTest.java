package co.edu.uco.notification.infrastructure.adapter.in.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.exception.AttachmentObjectChangedException;
import co.edu.uco.notification.core.port.in.ScanAttachmentUploadUseCase;
import co.edu.uco.notification.core.port.out.AttachmentScanRequestPort;
import co.edu.uco.notification.infrastructure.config.AttachmentScanTopologyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
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
class AttachmentScanListenerResilienceTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final TenantId TENANT = TenantId.of("tenant-1");

  @MockBean private ScanAttachmentUploadUseCase scanUseCase;

  @MockBean(name = "attachmentScanDlqRecoverer")
  private MessageRecoverer deadLetterRecoverer;

  @Autowired private AttachmentScanRequestPort scanRequestPort;

  @Autowired private RabbitTemplate rabbitTemplate;

  @Autowired private AmqpAdmin amqpAdmin;

  @Autowired private AttachmentScanTopologyProperties topology;

  @BeforeEach
  void setUp() {
    reset(scanUseCase, deadLetterRecoverer);
    amqpAdmin.purgeQueue(topology.queue(), false);
    amqpAdmin.purgeQueue(topology.dlqQueue(), false);
  }

  private long messagesIn(final String queue) {
    return amqpAdmin.getQueueInfo(queue).getMessageCount();
  }

  private void awaitEmpty(final String queue) {
    final Instant deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline) && messagesIn(queue) > 0) {
      Mono.delay(Duration.ofMillis(100)).block();
    }
  }

  @Test
  void whenTheDeadLetterPublicationFailsTheBrokerStillRoutesTheMessageToTheDeadLetterQueue() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(any(), any()))
        .thenReturn(Mono.error(new AttachmentInspectionUnavailableException("clamav down")));
    doThrow(new AmqpException("dlq publication failed"))
        .when(deadLetterRecoverer)
        .recover(any(), any());

    scanRequestPort.requestScan(TENANT, uploadId).block();

    final Message dead = rabbitTemplate.receive(topology.dlqQueue(), 20_000);
    assertNotNull(dead);
    assertEquals(
        true, new String(dead.getBody(), StandardCharsets.UTF_8).contains(uploadId.value()));
    verify(scanUseCase, times(2)).scan(TENANT, uploadId);
    verify(deadLetterRecoverer, times(1)).recover(any(), any());
    awaitEmpty(topology.queue());
    assertEquals(0, messagesIn(topology.queue()));
    assertEquals(0, messagesIn(topology.dlqQueue()));
  }

  @Test
  void aChangedObjectThatExhaustsTheAttemptsFailsTheUploadWithScanExhausted() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(any(), any()))
        .thenReturn(Mono.error(new AttachmentObjectChangedException()));
    when(scanUseCase.failExhausted(any(), any())).thenReturn(Mono.empty());

    scanRequestPort.requestScan(TENANT, uploadId).block();

    verify(scanUseCase, timeout(20_000)).failExhausted(TENANT, uploadId);
    verify(scanUseCase, times(2)).scan(TENANT, uploadId);
    verify(deadLetterRecoverer, timeout(20_000).times(1)).recover(any(), any());
  }

  @Test
  void aTransientChangedObjectThatRecoversDoesNotFailTheUpload() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(any(), any()))
        .thenReturn(Mono.error(new AttachmentObjectChangedException()), Mono.empty());
    when(scanUseCase.failExhausted(any(), any())).thenReturn(Mono.empty());

    scanRequestPort.requestScan(TENANT, uploadId).block();

    verify(scanUseCase, timeout(20_000).times(2)).scan(TENANT, uploadId);
    awaitEmpty(topology.queue());
    Mono.delay(Duration.ofSeconds(1)).block();
    verify(scanUseCase, never()).failExhausted(any(), any());
    verify(deadLetterRecoverer, never()).recover(any(), any());
  }

  @Test
  void anUnavailableScannerThatExhaustsTheAttemptsDoesNotFailTheUploadByItself() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(any(), any()))
        .thenReturn(Mono.error(new AttachmentInspectionUnavailableException("clamav down")));
    when(scanUseCase.failExhausted(any(), any())).thenReturn(Mono.empty());

    scanRequestPort.requestScan(TENANT, uploadId).block();

    verify(deadLetterRecoverer, timeout(20_000).times(1)).recover(any(), any());
    verify(scanUseCase, never()).failExhausted(any(), any());
  }

  @Test
  void whenTheRetryPublicationFailsTheMessageIsNotLostNorRedeliveredForever() {
    final UploadId uploadId = UploadId.newId();
    when(scanUseCase.scan(any(), any()))
        .thenReturn(
            Mono.delay(Duration.ofSeconds(2))
                .then(Mono.error(new AttachmentInspectionUnavailableException("clamav down"))));

    scanRequestPort.requestScan(TENANT, uploadId).block();
    verify(scanUseCase, timeout(10_000)).scan(TENANT, uploadId);
    amqpAdmin.deleteExchange(topology.exchange());

    final Message dead = rabbitTemplate.receive(topology.dlqQueue(), 20_000);
    try {
      assertNotNull(dead);
      assertEquals(
          true, new String(dead.getBody(), StandardCharsets.UTF_8).contains(uploadId.value()));
      awaitEmpty(topology.queue());
      assertEquals(0, messagesIn(topology.queue()));
      Mono.delay(Duration.ofSeconds(2)).block();
      verify(scanUseCase, times(1)).scan(TENANT, uploadId);
      assertEquals(0, messagesIn(topology.dlqQueue()));
    } finally {
      final DirectExchange exchange = new DirectExchange(topology.exchange());
      amqpAdmin.declareExchange(exchange);
      final Binding binding =
          BindingBuilder.bind(new Queue(topology.queue())).to(exchange).with(topology.routingKey());
      amqpAdmin.declareBinding(binding);
    }
  }
}
