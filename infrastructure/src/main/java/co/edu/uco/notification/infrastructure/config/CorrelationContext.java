package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.CorrelationId;
import org.springframework.amqp.core.MessageProperties;
import reactor.util.context.ContextView;

public final class CorrelationContext {

  private CorrelationContext() {}

  public static CorrelationId from(final ContextView context) {
    return context.hasKey(CorrelationId.CONTEXT_KEY)
        ? CorrelationId.fromOrNull(String.valueOf(context.<Object>get(CorrelationId.CONTEXT_KEY)))
        : null;
  }

  public static void stamp(final MessageProperties properties, final CorrelationId correlationId) {
    if (correlationId != null) {
      properties.setHeader(CorrelationId.AMQP_HEADER, correlationId.value());
    }
  }

  public static CorrelationId fromHeaders(final MessageProperties properties) {
    final Object value = properties.getHeaders().get(CorrelationId.AMQP_HEADER);
    return value == null ? null : CorrelationId.fromOrNull(value.toString());
  }
}
