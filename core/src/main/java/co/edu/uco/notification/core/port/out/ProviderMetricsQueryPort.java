package co.edu.uco.notification.core.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public interface ProviderMetricsQueryPort {

  Mono<Snapshot> query(Instant now, Duration window, Duration lookback);

  record Snapshot(
      List<Counts> counts,
      Map<String, Double> latencyP95SecondsByProvider,
      List<ErrorMark> errors) {}

  record Counts(
      String provider, String channel, double delivered, double failed, double recoverable) {}

  record ErrorMark(String provider, String channel, String result, Instant at) {}
}
