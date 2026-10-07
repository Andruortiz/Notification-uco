package co.edu.uco.notification.infrastructure.support;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class InMemoryTracingConfig {

  @Bean
  public InMemorySpans inMemorySpans() {
    return new InMemorySpans(InMemorySpanExporter.create());
  }

  @Bean
  public SpanProcessor inMemorySpanProcessor(final InMemorySpans spans) {
    return SimpleSpanProcessor.create(spans.exporter());
  }
}
