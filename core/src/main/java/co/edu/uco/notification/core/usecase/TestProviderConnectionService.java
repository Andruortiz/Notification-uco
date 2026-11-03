package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.ProviderTestInProgressException;
import co.edu.uco.notification.core.port.in.ProviderProbeResult;
import co.edu.uco.notification.core.port.in.TestProviderConnectionUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.ProviderProbePort;
import co.edu.uco.notification.core.port.out.ProviderProbeRegistry;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

public final class TestProviderConnectionService implements TestProviderConnectionUseCase {

  private record ProbeKey(ChannelType channel, ProviderId providerId) {}

  private final ChannelCatalogPort channelCatalogPort;
  private final NotificationSenderRegistry senderRegistry;
  private final ProviderProbeRegistry probeRegistry;
  private final Clock clock;
  private final Duration timeout;
  private final Set<ProbeKey> inFlight = ConcurrentHashMap.newKeySet();

  public TestProviderConnectionService(
      final ChannelCatalogPort channelCatalogPort,
      final NotificationSenderRegistry senderRegistry,
      final ProviderProbeRegistry probeRegistry,
      final Clock clock,
      final Duration timeout) {
    this.channelCatalogPort =
        Preconditions.requireNonNull(channelCatalogPort, "channelCatalogPort must not be null");
    this.senderRegistry =
        Preconditions.requireNonNull(senderRegistry, "senderRegistry must not be null");
    this.probeRegistry =
        Preconditions.requireNonNull(probeRegistry, "probeRegistry must not be null");
    this.clock = Preconditions.requireNonNull(clock, "clock must not be null");
    this.timeout = Preconditions.requireNonNull(timeout, "timeout must not be null");
  }

  @Override
  public Mono<ProviderProbeResult> test(final ChannelType channel, final ProviderId providerId) {
    Preconditions.requireNonNull(channel, "channel must not be null");
    Preconditions.requireNonNull(providerId, "providerId must not be null");
    return channelCatalogPort
        .findAllRoutes()
        .filter(route -> route.channelType().equals(channel))
        .next()
        .switchIfEmpty(Mono.error(new ChannelNotAvailableException(channel)))
        .flatMap(
            route ->
                route.providers().contains(providerId)
                    ? exclusively(new ProbeKey(channel, providerId))
                    : Mono.error(
                        new IllegalArgumentException(
                            "providerId is not a provider of the channel " + channel.value())));
  }

  private Mono<ProviderProbeResult> exclusively(final ProbeKey key) {
    return Mono.defer(
        () -> {
          if (!inFlight.add(key)) {
            return Mono.error(new ProviderTestInProgressException());
          }
          return run(key).doFinally(signal -> inFlight.remove(key));
        });
  }

  private Mono<ProviderProbeResult> run(final ProbeKey key) {
    final Optional<ProviderProbePort> probe = probeRegistry.find(key.providerId());
    final Optional<NotificationSenderPort> sender = senderRegistry.find(key.providerId());
    if (probe.isEmpty() || sender.isEmpty()) {
      return Mono.just(result(key, 0L, ProbeErrorCode.ADAPTER_MISSING));
    }
    if (sender.get().disabledReason().isPresent()) {
      return Mono.just(result(key, 0L, ProbeErrorCode.CREDENTIALS_MISSING));
    }
    return Mono.defer(
        () -> {
          final Instant startedAt = clock.instant();
          return probe
              .get()
              .probe()
              .timeout(timeout)
              .map(outcome -> result(key, elapsedMs(startedAt), outcome.errorCode()))
              .onErrorResume(
                  error ->
                      Mono.fromSupplier(() -> result(key, elapsedMs(startedAt), codeOf(error))))
              .switchIfEmpty(
                  Mono.fromSupplier(
                      () -> result(key, elapsedMs(startedAt), ProbeErrorCode.PROVIDER_ERROR)));
        });
  }

  private long elapsedMs(final Instant startedAt) {
    return Duration.between(startedAt, clock.instant()).toMillis();
  }

  private static ProbeErrorCode codeOf(final Throwable error) {
    return error instanceof TimeoutException
        ? ProbeErrorCode.TIMEOUT
        : ProbeErrorCode.PROVIDER_ERROR;
  }

  private static ProviderProbeResult result(
      final ProbeKey key, final long latencyMs, final ProbeErrorCode errorCode) {
    return new ProviderProbeResult(key.providerId(), key.channel(), latencyMs, errorCode);
  }
}
