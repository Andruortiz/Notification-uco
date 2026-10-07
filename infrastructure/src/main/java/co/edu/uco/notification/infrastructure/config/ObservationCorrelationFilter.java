package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.LogSanitizer;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageReceiverContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageSenderContext;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

public class ObservationCorrelationFilter implements ObservationFilter {

  @Override
  public Observation.Context map(final Observation.Context context) {
    sanitize(context);
    final CorrelationId correlationId = resolve(context);
    if (correlationId != null) {
      context.addHighCardinalityKeyValue(
          KeyValue.of(CorrelationId.CONTEXT_KEY, correlationId.value()));
    }
    return context;
  }

  private static void sanitize(final Observation.Context context) {
    for (final KeyValue entry : context.getLowCardinalityKeyValues()) {
      final String clean = clean(entry.getValue());
      if (!clean.equals(entry.getValue())) {
        context.addLowCardinalityKeyValue(KeyValue.of(entry.getKey(), clean));
      }
    }
    for (final KeyValue entry : context.getHighCardinalityKeyValues()) {
      final String clean = clean(entry.getValue());
      if (!clean.equals(entry.getValue())) {
        context.addHighCardinalityKeyValue(KeyValue.of(entry.getKey(), clean));
      }
    }
  }

  private static String clean(final String value) {
    return LogSanitizer.scrub(LogSanitizer.safe(value));
  }

  private static CorrelationId resolve(final Observation.Context context) {
    final Object explicit = context.get(CorrelationId.CONTEXT_KEY);
    if (explicit instanceof String text) {
      return CorrelationId.fromOrNull(text);
    }
    if (context instanceof ServerRequestObservationContext server) {
      final ServerHttpResponse response = server.getResponse();
      return response == null
          ? null
          : CorrelationId.fromOrNull(response.getHeaders().getFirst(CorrelationId.HEADER));
    }
    if (context instanceof RabbitMessageSenderContext sender) {
      return fromMessage(sender.getCarrier());
    }
    if (context instanceof RabbitMessageReceiverContext receiver) {
      return fromMessage(receiver.getCarrier());
    }
    return CorrelationId.fromOrNull(MDC.get(CorrelationId.CONTEXT_KEY));
  }

  private static CorrelationId fromMessage(final Message message) {
    return message == null ? null : CorrelationContext.fromHeaders(message.getMessageProperties());
  }
}
