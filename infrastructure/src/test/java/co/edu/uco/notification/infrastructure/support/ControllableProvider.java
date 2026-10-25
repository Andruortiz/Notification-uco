package co.edu.uco.notification.infrastructure.support;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.ProviderResult;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

public final class ControllableProvider implements NotificationSenderPort {

  private final ProviderId providerId;
  private final AtomicReference<AttemptResult> result =
      new AtomicReference<>(AttemptResult.RECOVERABLE_FAILURE);
  private final AtomicInteger invocations = new AtomicInteger();

  public ControllableProvider(final String providerId) {
    this.providerId = ProviderId.of(providerId);
  }

  public void respondWith(final AttemptResult newResult) {
    result.set(newResult);
  }

  public int invocations() {
    return invocations.get();
  }

  @Override
  public Mono<ProviderResult> send(final Notification notification) {
    invocations.incrementAndGet();
    return Mono.just(ProviderResult.of(result.get()));
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
