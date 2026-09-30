package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.rabbit.attachment-scan")
public record AttachmentScanTopologyProperties(
    String exchange,
    String routingKey,
    String queue,
    String dlqExchange,
    String dlqRoutingKey,
    String dlqQueue) {}
