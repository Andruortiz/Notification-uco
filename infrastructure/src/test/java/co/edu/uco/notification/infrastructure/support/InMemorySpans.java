package co.edu.uco.notification.infrastructure.support;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

public final class InMemorySpans {

  public static final String DISPATCH = "notification.dispatch";
  public static final String PROVIDER_CALL = "notification.provider.call";

  private static final AttributeKey<String> CORRELATION = AttributeKey.stringKey("correlationId");

  private final InMemorySpanExporter exporter;

  public InMemorySpans(final InMemorySpanExporter exporter) {
    this.exporter = exporter;
  }

  public InMemorySpanExporter exporter() {
    return exporter;
  }

  public List<SpanData> finished() {
    return exporter.getFinishedSpanItems();
  }

  public static String correlationOf(final SpanData span) {
    return span.getAttributes().get(CORRELATION);
  }

  public List<SpanData> withCorrelation(final String correlationId) {
    return finished().stream().filter(span -> correlationId.equals(correlationOf(span))).toList();
  }

  public List<SpanData> ofTrace(final String traceId) {
    return finished().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
  }

  public static boolean hasFullJourney(final List<SpanData> spans) {
    return spans.stream().anyMatch(span -> span.getKind() == SpanKind.SERVER)
        && spans.stream().anyMatch(span -> span.getKind() == SpanKind.PRODUCER)
        && spans.stream().anyMatch(span -> span.getKind() == SpanKind.CONSUMER)
        && spans.stream().anyMatch(span -> span.getName().equals(DISPATCH))
        && spans.stream().anyMatch(span -> span.getName().equals(PROVIDER_CALL));
  }

  public List<SpanData> awaitFullJourney(final String correlationId, final Duration limit)
      throws InterruptedException {
    return await(correlationId, InMemorySpans::hasFullJourney, limit);
  }

  public List<SpanData> await(
      final String correlationId, final Predicate<List<SpanData>> condition, final Duration limit)
      throws InterruptedException {
    final Instant deadline = Instant.now().plus(limit);
    List<SpanData> spans = withCorrelation(correlationId);
    while (!condition.test(spans) && Instant.now().isBefore(deadline)) {
      Thread.sleep(100);
      spans = withCorrelation(correlationId);
    }
    return spans;
  }

  public static long count(final List<SpanData> spans, final String name) {
    return spans.stream().filter(span -> span.getName().equals(name)).count();
  }
}
