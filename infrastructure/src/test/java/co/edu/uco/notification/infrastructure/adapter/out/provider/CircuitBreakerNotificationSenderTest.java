package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ExternalId;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.domain.valueobject.NotificationDetails;
import co.edu.uco.notification.core.domain.valueobject.NotificationRouting;
import co.edu.uco.notification.core.domain.valueobject.Priority;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.Recipient;
import co.edu.uco.notification.core.domain.valueobject.RecipientId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.ProviderCircuitOpenException;
import co.edu.uco.notification.core.exception.ProviderDisabledException;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.ProviderResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CircuitBreakerNotificationSenderTest {

  private static final ProviderCircuitProperties PROPERTIES =
      new ProviderCircuitProperties(50f, 4, 4, Duration.ofMillis(300), 1);

  private static Notification notification() {
    final Notification notification =
        Notification.accept(
            new NotificationRouting(
                TenantId.of("tenant-1"),
                ExternalId.of("order-42"),
                ChannelType.of("EMAIL"),
                RecipientId.of("recipient-1"),
                Recipient.of("alice@example.com")),
            new NotificationDetails(NotificationContent.of("Body"), Priority.NORMAL));
    return notification;
  }

  private static NotificationSenderPort delegate(final String id) {
    final NotificationSenderPort delegate = mock(NotificationSenderPort.class);
    when(delegate.providerId()).thenReturn(ProviderId.of(id));
    return delegate;
  }

  private static void sendTimes(
      final CircuitBreakerNotificationSender sender, final Notification notification, final int n) {
    for (int i = 0; i < n; i++) {
      sender.send(notification).onErrorResume(error -> Mono.empty()).block();
    }
  }

  @Test
  void opensAtTheThresholdAndStopsInvokingTheDelegate() {
    final NotificationSenderPort delegate = delegate("a");
    when(delegate.send(any()))
        .thenReturn(Mono.just(ProviderResult.of(AttemptResult.RECOVERABLE_FAILURE)));
    final CircuitBreakerNotificationSender sender =
        new CircuitBreakerNotificationSender(delegate, PROPERTIES);
    final Notification notification = notification();

    sendTimes(sender, notification, 4);
    final long startedAt = System.nanoTime();
    StepVerifier.create(sender.send(notification))
        .expectError(ProviderCircuitOpenException.class)
        .verify();
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    verify(delegate, times(4)).send(any());
    assertTrue(elapsed.compareTo(Duration.ofMillis(200)) < 0, "skip took " + elapsed);
  }

  @Test
  void permanentFailuresNeverOpenTheCircuit() {
    final NotificationSenderPort delegate = delegate("a");
    when(delegate.send(any()))
        .thenReturn(Mono.just(ProviderResult.of(AttemptResult.PERMANENT_FAILURE)));
    final CircuitBreakerNotificationSender sender =
        new CircuitBreakerNotificationSender(delegate, PROPERTIES);
    final Notification notification = notification();

    sendTimes(sender, notification, 10);

    verify(delegate, times(10)).send(any());
  }

  @Test
  void disabledProviderErrorsAreIgnoredByTheCircuit() {
    final NotificationSenderPort delegate = delegate("a");
    when(delegate.send(any()))
        .thenReturn(Mono.error(new ProviderDisabledException(ProviderId.of("a"), "off")));
    final CircuitBreakerNotificationSender sender =
        new CircuitBreakerNotificationSender(delegate, PROPERTIES);
    final Notification notification = notification();

    sendTimes(sender, notification, 10);

    verify(delegate, times(10)).send(any());
  }

  @Test
  void exceptionsCountAsFailures() {
    final NotificationSenderPort delegate = delegate("a");
    when(delegate.send(any())).thenReturn(Mono.error(new IllegalStateException("down")));
    final CircuitBreakerNotificationSender sender =
        new CircuitBreakerNotificationSender(delegate, PROPERTIES);
    final Notification notification = notification();

    sendTimes(sender, notification, 4);

    StepVerifier.create(sender.send(notification))
        .expectError(ProviderCircuitOpenException.class)
        .verify();
  }

  @Test
  void halfOpenAdmitsATrialAndClosesOnSuccess() throws InterruptedException {
    final NotificationSenderPort delegate = delegate("a");
    when(delegate.send(any()))
        .thenReturn(Mono.just(ProviderResult.of(AttemptResult.RECOVERABLE_FAILURE)));
    final CircuitBreakerNotificationSender sender =
        new CircuitBreakerNotificationSender(delegate, PROPERTIES);
    final Notification notification = notification();
    sendTimes(sender, notification, 4);
    Thread.sleep(400);
    when(delegate.send(any())).thenReturn(Mono.just(ProviderResult.of(AttemptResult.ACCEPTED)));

    StepVerifier.create(sender.send(notification))
        .assertNext(result -> assertEquals(AttemptResult.ACCEPTED, result.result()))
        .verifyComplete();
    StepVerifier.create(sender.send(notification))
        .assertNext(result -> assertEquals(AttemptResult.ACCEPTED, result.result()))
        .verifyComplete();
  }

  @Test
  void circuitsAreIndependentPerProvider() {
    final NotificationSenderPort failing = delegate("a");
    when(failing.send(any()))
        .thenReturn(Mono.just(ProviderResult.of(AttemptResult.RECOVERABLE_FAILURE)));
    final NotificationSenderPort healthy = delegate("b");
    when(healthy.send(any())).thenReturn(Mono.just(ProviderResult.of(AttemptResult.ACCEPTED)));
    final CircuitBreakerNotificationSender a =
        new CircuitBreakerNotificationSender(failing, PROPERTIES);
    final CircuitBreakerNotificationSender b =
        new CircuitBreakerNotificationSender(healthy, PROPERTIES);
    final Notification notification = notification();

    sendTimes(a, notification, 4);

    StepVerifier.create(a.send(notification))
        .expectError(ProviderCircuitOpenException.class)
        .verify();
    StepVerifier.create(b.send(notification))
        .assertNext(result -> assertEquals(AttemptResult.ACCEPTED, result.result()))
        .verifyComplete();
    verify(healthy, times(1)).send(any());
    verify(failing, never()).disabledReason();
  }
}
