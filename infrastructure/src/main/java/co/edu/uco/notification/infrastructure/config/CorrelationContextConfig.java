package co.edu.uco.notification.infrastructure.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration
public class CorrelationContextConfig {

  @PostConstruct
  void propagateLogFieldsToTheMdc() {
    for (final String key : LogFields.CONTEXT_KEYS) {
      ContextRegistry.getInstance()
          .registerThreadLocalAccessor(
              key, () -> MDC.get(key), value -> MDC.put(key, value), () -> MDC.remove(key));
    }
    Hooks.enableAutomaticContextPropagation();
  }
}
