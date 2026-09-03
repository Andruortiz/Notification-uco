package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.rabbit")
public record RabbitTopologyProperties(Dispatch dispatch, String eventsExchange) {

  public record Dispatch(String exchange, String routingKey, String queue) {}
}
