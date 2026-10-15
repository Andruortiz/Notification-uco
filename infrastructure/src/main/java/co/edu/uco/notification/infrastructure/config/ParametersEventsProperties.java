package co.edu.uco.notification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.parameters.events")
public record ParametersEventsProperties(
    String exchange,
    String exchangeType,
    String routingKey,
    String queue,
    String dlqExchange,
    String dlqRoutingKey,
    String dlqQueue,
    Integer maxAttempts,
    Integer consumerConcurrency) {

  private static final String DEFAULT_EXCHANGE_TYPE = "topic";
  private static final String DLQ_SUFFIX = ".dlq";
  private static final int DEFAULT_MAX_ATTEMPTS = 3;
  private static final int DEFAULT_CONSUMER_CONCURRENCY = 1;

  public ParametersEventsProperties {
    exchange = trimmed(exchange);
    routingKey = trimmed(routingKey);
    queue = trimmed(queue);
    exchangeType = trimmed(exchangeType).isEmpty() ? DEFAULT_EXCHANGE_TYPE : trimmed(exchangeType);
    if (!exchange.isEmpty()) {
      if (routingKey.isEmpty()) {
        throw new IllegalArgumentException(
            "notification.parameters.events.routing-key is required when"
                + " notification.parameters.events.exchange is set");
      }
      if (queue.isEmpty()) {
        throw new IllegalArgumentException(
            "notification.parameters.events.queue is required when"
                + " notification.parameters.events.exchange is set");
      }
    }
    dlqExchange = trimmed(dlqExchange).isEmpty() ? derived(queue) : trimmed(dlqExchange);
    dlqRoutingKey = trimmed(dlqRoutingKey).isEmpty() ? derived(queue) : trimmed(dlqRoutingKey);
    dlqQueue = trimmed(dlqQueue).isEmpty() ? derived(queue) : trimmed(dlqQueue);
    if (maxAttempts == null || maxAttempts < 1) {
      maxAttempts = DEFAULT_MAX_ATTEMPTS;
    }
    if (consumerConcurrency == null || consumerConcurrency < 1) {
      consumerConcurrency = DEFAULT_CONSUMER_CONCURRENCY;
    }
  }

  public boolean isActive() {
    return !exchange.isEmpty();
  }

  private static String derived(final String queue) {
    return queue.isEmpty() ? "" : queue + DLQ_SUFFIX;
  }

  private static String trimmed(final String value) {
    return value == null ? "" : value.trim();
  }
}
