package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.rabbit")
public record RabbitTopologyProperties(Dispatch dispatch, Dlq dlq, String eventsExchange) {

  public record Dispatch(String exchange, String routingKey, String queue) {}

  public record Dlq(String exchange, String routingKey, String queue) {}
}
