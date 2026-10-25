package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.*;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.infrastructure.config.SimulatedProviderProperties;
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
            new SimulatedProviderProperties(AttemptResult.RECOVERABLE_FAILURE, true));

    StepVerifier.create(provider.send(aNotification()))
        .assertNext(
            providerResult ->
                assertEquals(AttemptResult.RECOVERABLE_FAILURE, providerResult.result()))
        .verifyComplete();
  }

  @Test
  void resultDefaultsToAcceptedWhenNotConfigured() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null, true));

    StepVerifier.create(provider.send(aNotification()))
        .assertNext(providerResult -> assertEquals(AttemptResult.ACCEPTED, providerResult.result()))
        .verifyComplete();
  }

  @Test
  void sendRejectsNullNotification() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null, true));

    assertThrows(NullPointerException.class, () -> provider.send(null));
  }

  @Test
  void declaresTheSimulatedProviderId() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null, true));

    assertEquals(ProviderId.of("simulated"), provider.providerId());
  }

  @Test
  void isDisabledWhenNotEnabledAndRefusesToSend() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null, false));

    assertTrue(provider.disabledReason().isPresent());
    StepVerifier.create(provider.send(aNotification()))
        .expectError(ProviderDisabledException.class)
        .verify();
  }

  @Test
  void isEnabledWhenConfiguredEnabled() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null, true));

    assertTrue(provider.disabledReason().isEmpty());
  }

  @Test
  void supportsAttachments() {
    final SimulatedNotificationProvider provider =
        new SimulatedNotificationProvider(new SimulatedProviderProperties(null, true));

    assertTrue(provider.supportsAttachments());
  }

  @Test
  void constructorRejectsNullProperties() {
    assertThrows(NullPointerException.class, () -> new SimulatedNotificationProvider(null));
  }
}
