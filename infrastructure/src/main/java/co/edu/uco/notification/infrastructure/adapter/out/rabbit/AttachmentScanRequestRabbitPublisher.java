package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.port.out.AttachmentScanRequestPort;
import co.edu.uco.notification.infrastructure.config.AttachmentScanTopologyProperties;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.ReactorObservations;
import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class AttachmentScanRequestRabbitPublisher implements AttachmentScanRequestPort {

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final AttachmentScanTopologyProperties topology;

  public AttachmentScanRequestRabbitPublisher(
      final RabbitTemplate rabbitTemplate,
      final ObjectMapper objectMapper,
      final AttachmentScanTopologyProperties topology) {
    this.rabbitTemplate =
        Preconditions.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
    this.objectMapper = Preconditions.requireNonNull(objectMapper, "objectMapper must not be null");
    this.topology = Preconditions.requireNonNull(topology, "topology must not be null");
  }

  @Override
  public Mono<Void> requestScan(final TenantId tenantId, final UploadId uploadId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(uploadId, "uploadId must not be null");
    return Mono.deferContextual(
        context ->
            Mono.<Void>fromRunnable(
                    () ->
                        ReactorObservations.run(
                            context,
                            () ->
                                rabbitTemplate.convertAndSend(
                                    topology.exchange(),
                                    topology.routingKey(),
                                    toJson(
                                        new AttachmentScanRequest(
                                            tenantId.value(), uploadId.value())),
                                    message -> {
                                      message
                                          .getMessageProperties()
                                          .setMessageId(UUID.randomUUID().toString());
                                      CorrelationContext.stamp(
                                          message.getMessageProperties(),
                                          CorrelationContext.from(context));
                                      return message;
                                    })))
                .subscribeOn(Schedulers.boundedElastic()));
  }

  private String toJson(final AttachmentScanRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize the scan request", e);
    }
  }
}
