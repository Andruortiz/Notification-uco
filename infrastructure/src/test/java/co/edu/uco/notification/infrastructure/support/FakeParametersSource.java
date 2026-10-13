package co.edu.uco.notification.infrastructure.support;

import co.edu.uco.notification.core.domain.configuration.ConfigurationChange;
import co.edu.uco.notification.core.exception.ParametersUnavailableException;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.core.port.out.ParametersSourcePort;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import reactor.core.publisher.Mono;

public final class FakeParametersSource implements ParametersSourcePort {

  private final AtomicReference<ConfigurationChange> published = new AtomicReference<>();
  private final AtomicBoolean failing = new AtomicBoolean(true);
  private final AtomicInteger calls = new AtomicInteger();

  @Override
  public Mono<ConfigurationChange> fetchState() {
    calls.incrementAndGet();
    if (failing.get()) {
      return Mono.error(new ParametersUnavailableException("fake source is down"));
    }
    final ConfigurationChange change = published.get();
    return change == null ? Mono.empty() : Mono.just(change);
  }

  public void publish(final long version, final Map<String, Object> values) {
    published.set(new ConfigurationChange(version, values));
    failing.set(false);
  }

  public long publishNext(final ConfigurationView view, final Map<String, Object> values) {
    final long version = view.snapshot().version() + 1;
    publish(version, values);
    return version;
  }

  public Duration publishNextAndAwaitAdoption(
      final ConfigurationView view, final Map<String, Object> values, final Duration limit) {
    final Instant started = Instant.now();
    final long version = publishNext(view, values);
    final Instant deadline = started.plus(limit);
    while (Instant.now().isBefore(deadline) && view.snapshot().version() < version) {
      pause();
    }
    return Duration.between(started, Instant.now());
  }

  public int calls() {
    return calls.get();
  }

  public static boolean await(final BooleanSupplier condition, final Duration limit) {
    final Instant deadline = Instant.now().plus(limit);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return true;
      }
      pause();
    }
    return condition.getAsBoolean();
  }

  private static void pause() {
    try {
      Thread.sleep(50);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
