package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.utils.CorrelationId;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class ObservedNotificationSenderTest {

  private final List<Observation.Context> stopped = new ArrayList<>();
  private final List<Observation.Context> started = new ArrayList<>();
  private final ObservationRegistry registry = ObservationRegistry.create();

  ObservedNotificationSenderTest() {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(final Observation.Context context) {
                return true;
              }

              @Override
              public void onStart(final Observation.Context context) {
                started.add(context);
              }

              @Override
              public void onStop(final Observation.Context context) {
                stopped.add(context);
              }
            });
  }

  private static Notification notification() {
    final Notification notification = mock(Notification.class);
    when(notification.channelType()).thenReturn(ChannelType.of("EMAIL"));
    return notification;
  }

  private static NotificationSenderPort delegate(final Mono<AttemptResult> result) {
    final NotificationSenderPort delegate = mock(NotificationSenderPort.class);
    when(delegate.providerId()).thenReturn(ProviderId.of("brevo"));
    when(delegate.disabledReason()).thenReturn(Optional.of("off"));
    when(delegate.supportsAttachments()).thenReturn(true);
    when(delegate.send(org.mockito.ArgumentMatchers.any())).thenReturn(result);
    return delegate;
  }

  private static String value(final Observation.Context context, final String key) {
    return context.getLowCardinalityKeyValues().stream()
        .filter(entry -> entry.getKey().equals(key))
        .map(entry -> entry.getValue())
        .findFirst()
        .orElse(null);
  }

  @Test
  void theCallIsATaggedSpanWithProviderChannelAndResult() {
    final ObservedNotificationSender sender =
        new ObservedNotificationSender(delegate(Mono.just(AttemptResult.ACCEPTED)), registry);

    StepVerifier.create(
            sender
                .send(notification())
                .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, "corr-1")))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    assertEquals(1, stopped.size());
    final Observation.Context context = stopped.get(0);
    assertEquals("brevo", value(context, "provider"));
    assertEquals("EMAIL", value(context, "channel"));
    assertEquals("delivered", value(context, "result"));
    assertEquals("corr-1", context.get(CorrelationId.CONTEXT_KEY));
  }

  @Test
  void aFailureIsRecordedAndTheSpanIsStillClosed() {
    final ObservedNotificationSender sender =
        new ObservedNotificationSender(
            delegate(Mono.error(new IllegalStateException("boom"))), registry);

    StepVerifier.create(sender.send(notification()))
        .expectError(IllegalStateException.class)
        .verify();

    assertEquals(1, stopped.size());
    assertNotNull(stopped.get(0).getError());
  }

  @Test
  void theCallIsAChildOfTheObservationInTheReactorContext() {
    final Observation parent = Observation.start("test.parent", registry);
    final AtomicReference<Observation.Context> child = new AtomicReference<>();
    final ObservedNotificationSender sender =
        new ObservedNotificationSender(delegate(Mono.just(AttemptResult.ACCEPTED)), registry);

    StepVerifier.create(
            sender
                .send(notification())
                .contextWrite(Context.of(ObservationThreadLocalAccessor.KEY, parent)))
        .expectNext(AttemptResult.ACCEPTED)
        .verifyComplete();

    child.set(
        started.stream()
            .filter(context -> context != null && context.getParentObservation() != null)
            .findFirst()
            .orElse(null));
    assertNotNull(child.get());
    assertSame(parent, child.get().getParentObservation());
    parent.stop();
  }

  @Test
  void identityAndCapabilitiesAreDelegated() {
    final ObservedNotificationSender sender =
        new ObservedNotificationSender(delegate(Mono.empty()), registry);

    assertEquals(ProviderId.of("brevo"), sender.providerId());
    assertEquals(Optional.of("off"), sender.disabledReason());
    assertTrue(sender.supportsAttachments());
  }
}
