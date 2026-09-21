package co.edu.uco.notification.core.port.out;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ProviderNotAvailableException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class NotificationSenderRegistryTest {

  private static final class FakeSender implements NotificationSenderPort {

    private final ProviderId providerId;
    private final List<Notification> received = new ArrayList<>();

    private FakeSender(final ProviderId providerId) {
      this.providerId = providerId;
    }

    @Override
    public Mono<AttemptResult> send(final Notification notification) {
      received.add(notification);
      return Mono.just(AttemptResult.ACCEPTED);
    }

    @Override
    public ProviderId providerId() {
      return providerId;
    }
  }

  private static FakeSender fake(final String providerId) {
    return new FakeSender(ProviderId.of(providerId));
  }

  @Test
  void resolveReturnsTheAdapterThatDeclaresTheRequestedProviderId() {
    final FakeSender fakeA = fake("fake-a");
    final FakeSender fakeB = fake("fake-b");
    final NotificationSenderRegistry registry =
        new NotificationSenderRegistry(List.of(fakeA, fakeB));

    assertSame(fakeA, registry.resolve(ProviderId.of("fake-a")));
    assertSame(fakeB, registry.resolve(ProviderId.of("fake-b")));
  }

  @Test
  void resolveFailsWithProviderNotAvailableWhenNoAdapterDeclaresTheProviderId() {
    final NotificationSenderRegistry registry =
        new NotificationSenderRegistry(List.of(fake("fake-a")));

    final ProviderNotAvailableException error =
        assertThrows(
            ProviderNotAvailableException.class, () -> registry.resolve(ProviderId.of("fantasma")));

    assertTrue(error.getMessage().contains("fantasma"));
  }

  @Test
  void resolveRejectsANullProviderId() {
    final NotificationSenderRegistry registry =
        new NotificationSenderRegistry(List.of(fake("fake-a")));

    assertThrows(NullPointerException.class, () -> registry.resolve(null));
  }

  @Test
  void constructorRejectsTwoAdaptersDeclaringTheSameProviderId() {
    final List<NotificationSenderPort> senders = List.of(fake("fake-a"), fake("fake-a"));

    final IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new NotificationSenderRegistry(senders));

    assertTrue(error.getMessage().contains("fake-a"));
  }

  @Test
  void constructorRejectsAnAdapterWithoutProviderId() {
    final List<NotificationSenderPort> senders = List.of(new FakeSender(null));

    assertThrows(NullPointerException.class, () -> new NotificationSenderRegistry(senders));
  }

  @Test
  void constructorRejectsANullCollection() {
    assertThrows(NullPointerException.class, () -> new NotificationSenderRegistry(null));
  }

  @Test
  void anEmptyRegistryIsValidButResolvesNothing() {
    final NotificationSenderRegistry registry = new NotificationSenderRegistry(List.of());

    assertThrows(
        ProviderNotAvailableException.class, () -> registry.resolve(ProviderId.of("fake-a")));
  }
}
