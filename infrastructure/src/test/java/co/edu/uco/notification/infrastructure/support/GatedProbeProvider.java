package co.edu.uco.notification.infrastructure.support;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.ProbeOutcome;
import co.edu.uco.notification.core.port.out.ProviderProbePort;
import co.edu.uco.notification.core.port.out.ProviderResult;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public final class GatedProbeProvider implements NotificationSenderPort, ProviderProbePort {

  private final ProviderId providerId;
  private final AtomicReference<Sinks.One<ProbeOutcome>> gate = new AtomicReference<>(Sinks.one());
  private final AtomicInteger invocations = new AtomicInteger();

  public GatedProbeProvider(final String providerId) {
    this.providerId = ProviderId.of(providerId);
  }

  public void release(final ProbeOutcome outcome) {
    gate.get().tryEmitValue(outcome);
  }

  public void rearm() {
    gate.set(Sinks.one());
  }

  public int invocations() {
    return invocations.get();
  }

  @Override
  public Mono<ProbeOutcome> probe() {
    return Mono.defer(
        () -> {
          invocations.incrementAndGet();
          return gate.get().asMono();
        });
  }

  @Override
  public Mono<ProviderResult> send(final Notification notification) {
    return Mono.just(ProviderResult.of(AttemptResult.ACCEPTED));
  }

  @Override
  public ProviderId providerId() {
    return providerId;
  }

  @Override
  public Optional<String> disabledReason() {
    return Optional.empty();
  }

  @Override
  public boolean supportsAttachments() {
    return true;
  }
}
