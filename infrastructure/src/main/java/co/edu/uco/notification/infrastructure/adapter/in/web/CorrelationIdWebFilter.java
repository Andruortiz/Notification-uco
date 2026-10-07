package co.edu.uco.notification.infrastructure.adapter.in.web;

import co.edu.uco.notification.utils.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

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
    exchange.getAttributes().put(CORRELATION_ATTRIBUTE, correlationId);
    exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId.value());
    return chain
        .filter(exchange)
        .contextWrite(context -> context.put(CorrelationId.CONTEXT_KEY, correlationId.value()));
  }
}
