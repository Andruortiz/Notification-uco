package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.TraceParent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

  public static final String CORRELATION_ATTRIBUTE =
      CorrelationIdWebFilter.class.getName() + ".CORRELATION_ID";

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 5;
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final CorrelationId correlationId =
        CorrelationId.fromOrNew(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
    final TraceParent traceParent =
        TraceParent.fromOrNull(exchange.getRequest().getHeaders().getFirst(TraceParent.HEADER));
    exchange.getAttributes().put(CORRELATION_ATTRIBUTE, correlationId);
    exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId.value());
    if (traceParent != null) {
      exchange.getResponse().getHeaders().set(TraceParent.HEADER, traceParent.value());
    }
    return chain
        .filter(exchange)
        .contextWrite(
            context -> {
              Context enriched = context.put(CorrelationId.CONTEXT_KEY, correlationId.value());
              if (traceParent != null) {
                enriched = enriched.put(TraceParent.CONTEXT_KEY, traceParent.value());
              }
              return enriched;
            });
  }
}
