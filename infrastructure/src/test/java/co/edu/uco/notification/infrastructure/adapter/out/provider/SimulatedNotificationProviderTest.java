package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class SimulatedNotificationProviderTest {

  private static Notification aNotification() {
    return Notification.accept(
        new NotificationRouting(
            TenantId.of("tenant-1"),
            ExternalId.of("order-42"),
            ChannelType.of("EMAIL"),
            RecipientId.of("recipient-1"),
            Recipient.of("alice@example.com")),
        new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
  }

  @Test
  void sendReturnsTheConfiguredResult() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(
            new SimulatedProviderProperties(AttemptResult.RECOVERABLE_FAILURE));

    StepVerifier.create(provider.send(aNotification()))
        .expectNext(AttemptResult.RECOVERABLE_FAILURE)
        .verifyComplete();
  }

  @Test
  void resultDefaultsToAcceptedWhenNotConfigured() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null));

    StepVerifier.create(provider.send(aNotification()))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();
  }

  @Test
  void sendRejectsNullNotification() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null));

    assertThrows(NullPointerException.class, () -> provider.send(null));
  }

  @Test
  void constructorRejectsNullProperties() {
    assertThrows(NullPointerException.class, () -> new SimulatedNotificationProvider(null));
  }
}
