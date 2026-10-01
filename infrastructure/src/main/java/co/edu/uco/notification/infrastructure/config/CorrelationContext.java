package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.TraceParent;
import org.springframework.amqp.core.MessageProperties;
import reactor.util.context.ContextView;

public final class CorrelationContext {

  private CorrelationContext() {}

  public static CorrelationId from(final ContextView context) {
    return context.hasKey(CorrelationId.CONTEXT_KEY)
        ? CorrelationId.fromOrNull(String.valueOf(context.<Object>get(CorrelationId.CONTEXT_KEY)))
        : null;
  }

  public static TraceParent traceFrom(final ContextView context) {
    return context.hasKey(TraceParent.CONTEXT_KEY)
        ? TraceParent.fromOrNull(String.valueOf(context.<Object>get(TraceParent.CONTEXT_KEY)))
        : null;
  }

  public static void stamp(
      final MessageProperties properties,
      final CorrelationId correlationId,
      final TraceParent traceParent) {
    if (correlationId != null) {
      properties.setHeader(CorrelationId.AMQP_HEADER, correlationId.value());
    }
    if (traceParent != null) {
      properties.setHeader(TraceParent.HEADER, traceParent.value());
    }
  }

  public static CorrelationId fromHeaders(final MessageProperties properties) {
    final Object value = properties.getHeaders().get(CorrelationId.AMQP_HEADER);
    return value == null ? null : CorrelationId.fromOrNull(value.toString());
  }

  public static TraceParent traceFromHeaders(final MessageProperties properties) {
    final Object value = properties.getHeaders().get(TraceParent.HEADER);
    return value == null ? null : TraceParent.fromOrNull(value.toString());
  }
}
