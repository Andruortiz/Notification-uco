package co.edu.uco.notification.infrastructure.adapter.out.metrics;

import co.edu.uco.notification.core.exception.ProviderMetricsUnavailableException;
import co.edu.uco.notification.core.port.out.ProviderMetricsQueryPort;
import co.edu.uco.notification.infrastructure.config.ProviderHealthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PrometheusProviderMetricsAdapter implements ProviderMetricsQueryPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(PrometheusProviderMetricsAdapter.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long STEP_SECONDS = 60;
  private static final String DEFAULT_URL = "http://localhost:9090";

  private final WebClient client;
  private final Duration timeout;

  public PrometheusProviderMetricsAdapter(final ProviderHealthProperties properties) {
    final String url = properties.prometheusUrl();
    this.client =
        WebClient.builder()
            .baseUrl(url == null || url.isBlank() ? DEFAULT_URL : url.strip())
            .build();
    this.timeout = properties.timeout();
  }

  @Override
  public Mono<Snapshot> query(final Instant now, final Duration window, final Duration lookback) {
    final long w = window.toSeconds();
    final String counts =
        "sum by (provider,channel,result)(increase(notification_dispatched_total"
            + "{result=~\"delivered|failed|recoverable\"}["
            + w
            + "s]))";
    final String latency =
        "histogram_quantile(0.95, sum by (le,provider)(rate("
            + "notification_provider_duration_seconds_bucket["
            + w
            + "s])))";
    final String errors =
        "sum by (provider,channel,result)(increase(notification_dispatched_total"
            + "{result=~\"recoverable|failed\"}["
            + STEP_SECONDS
            + "s]))";
    return Mono.zip(instant(counts), instant(latency), range(errors, now.minus(lookback), now))
        .map(t -> new Snapshot(countsOf(t.getT1()), latenciesOf(t.getT2()), errorsOf(t.getT3())))
        .timeout(timeout)
        .onErrorMap(PrometheusProviderMetricsAdapter::isSourceFailure, this::unavailable);
  }

  private static boolean isSourceFailure(final Throwable e) {
    return e instanceof WebClientResponseException
        || e instanceof WebClientRequestException
        || e instanceof TimeoutException;
  }

  private ProviderMetricsUnavailableException unavailable(final Throwable e) {
    final int status = e instanceof WebClientResponseException r ? r.getStatusCode().value() : 0;
    LOGGER.warn(
        "Prometheus query failed: cause={} status={}", e.getClass().getSimpleName(), status);
    return new ProviderMetricsUnavailableException();
  }

  private Mono<JsonNode> instant(final String promql) {
    return fetch(
        client.get().uri(u -> u.path("/api/v1/query").queryParam("query", "{q}").build(promql)));
  }

  private Mono<JsonNode> range(final String promql, final Instant start, final Instant end) {
    return fetch(
        client
            .get()
            .uri(
                u ->
                    u.path("/api/v1/query_range")
                        .queryParam("query", "{q}")
                        .queryParam("start", start.getEpochSecond())
                        .queryParam("end", end.getEpochSecond())
                        .queryParam("step", STEP_SECONDS)
                        .build(promql)));
  }

  private Mono<JsonNode> fetch(final WebClient.RequestHeadersSpec<?> request) {
    return request
        .retrieve()
        .bodyToMono(String.class)
        .map(MAPPER::readTree)
        .filter(body -> "success".equals(body.path("status").asString("")))
        .map(body -> body.path("data").path("result"))
        .switchIfEmpty(Mono.error(new ProviderMetricsUnavailableException()));
  }

  private static List<Counts> countsOf(final JsonNode result) {
    final Map<List<String>, double[]> byKey = new HashMap<>();
    for (final JsonNode series : result) {
      final JsonNode metric = series.path("metric");
      final List<String> key =
          List.of(metric.path("provider").asString(""), metric.path("channel").asString(""));
      final double value = number(series.path("value").path(1));
      final double[] acc = byKey.computeIfAbsent(key, k -> new double[3]);
      if (!Double.isNaN(value)) {
        switch (metric.path("result").asString("")) {
          case "delivered" -> acc[0] += value;
          case "failed" -> acc[1] += value;
          case "recoverable" -> acc[2] += value;
          default -> {}
        }
      }
    }
    final List<Counts> counts = new ArrayList<>();
    byKey.forEach(
        (key, acc) -> counts.add(new Counts(key.get(0), key.get(1), acc[0], acc[1], acc[2])));
    return counts;
  }

  private static Map<String, Double> latenciesOf(final JsonNode result) {
    final Map<String, Double> latencies = new HashMap<>();
    for (final JsonNode series : result) {
      final double value = number(series.path("value").path(1));
      if (Double.isFinite(value)) {
        latencies.put(series.path("metric").path("provider").asString(""), value);
      }
    }
    return latencies;
  }

  private static List<ErrorMark> errorsOf(final JsonNode result) {
    final List<ErrorMark> marks = new ArrayList<>();
    for (final JsonNode series : result) {
      final JsonNode metric = series.path("metric");
      for (final JsonNode point : series.path("values")) {
        if (number(point.path(1)) > 0) {
          marks.add(
              new ErrorMark(
                  metric.path("provider").asString(""),
                  metric.path("channel").asString(""),
                  metric.path("result").asString(""),
                  Instant.ofEpochSecond((long) point.path(0).asDouble(0))));
        }
      }
    }
    return marks;
  }

  private static double number(final JsonNode node) {
    try {
      return Double.parseDouble(node.asString(""));
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }
}
