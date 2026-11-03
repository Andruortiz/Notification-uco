package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.ProviderTestInProgressException;
import co.edu.uco.notification.core.port.in.ProviderProbeResult;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.port.out.NotificationSenderPort;
import co.edu.uco.notification.core.port.out.NotificationSenderRegistry;
import co.edu.uco.notification.core.port.out.ProbeOutcome;
import co.edu.uco.notification.core.port.out.ProviderProbePort;
import co.edu.uco.notification.core.port.out.ProviderProbeRegistry;
import co.edu.uco.notification.core.port.out.ProviderResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class TestProviderConnectionServiceTest {

  private static final ChannelType EMAIL = ChannelType.of("EMAIL");
  private static final ChannelType SMS = ChannelType.of("SMS");
  private static final ProviderId BREVO = ProviderId.of("brevo");
  private static final ProviderId SIMULATED = ProviderId.of("simulated");
  private static final Duration LIMIT = Duration.ofSeconds(5);

  private record FakeSender(ProviderId providerId, Optional<String> disabledReason)
      implements NotificationSenderPort {

    @Override
    public Mono<ProviderResult> send(final Notification notification) {
      return Mono.just(ProviderResult.of(AttemptResult.ACCEPTED));
    }

    @Override
    public boolean supportsAttachments() {
      return false;
    }
  }

  private record FakeProbe(ProviderId providerId, Mono<ProbeOutcome> outcome)
      implements ProviderProbePort {

    @Override
    public Mono<ProbeOutcome> probe() {
      return outcome;
    }
  }

  private static final class SteppingClock extends Clock {

    private final AtomicInteger calls = new AtomicInteger();
    private final long stepMillis;

    private SteppingClock(final long stepMillis) {
      this.stepMillis = stepMillis;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.EPOCH.plusMillis(calls.getAndIncrement() * stepMillis);
    }
  }

  private final ChannelCatalogPort catalog = mock(ChannelCatalogPort.class);

  private TestProviderConnectionService service(
      final Clock clock, final List<NotificationSenderPort> senders, final ProviderProbePort... p) {
    when(catalog.findAllRoutes())
        .thenReturn(
            Flux.just(
                new ChannelRoute(EMAIL, List.of(BREVO, SIMULATED), null),
                new ChannelRoute(SMS, List.of(SIMULATED), null)));
    return new TestProviderConnectionService(
        catalog,
        new NotificationSenderRegistry(senders),
        new ProviderProbeRegistry(List.of(p)),
        clock,
        LIMIT);
  }

  private static NotificationSenderPort enabled(final ProviderId id) {
    return new FakeSender(id, Optional.empty());
  }

  @Test
  void aSuccessfulProbeReportsOkWithTheMeasuredLatency() {
    final TestProviderConnectionService service =
        service(
            new SteppingClock(150),
            List.of(enabled(BREVO)),
            new FakeProbe(BREVO, Mono.just(ProbeOutcome.ok())));

    StepVerifier.create(service.test(EMAIL, BREVO))
        .assertNext(
            result -> {
              assertTrue(result.isOk());
              assertNull(result.errorCode());
              assertEquals(150L, result.latencyMs());
              assertEquals(BREVO, result.providerId());
              assertEquals(EMAIL, result.channel());
            })
        .verifyComplete();
  }

  @Test
  void aFailedProbeKeepsItsClosedErrorCode() {
    final TestProviderConnectionService service =
        service(
            new SteppingClock(10),
            List.of(enabled(BREVO)),
            new FakeProbe(BREVO, Mono.just(ProbeOutcome.failed(ProbeErrorCode.AUTH_REJECTED))));

    StepVerifier.create(service.test(EMAIL, BREVO))
        .assertNext(result -> assertEquals(ProbeErrorCode.AUTH_REJECTED, result.errorCode()))
        .verifyComplete();
  }

  @Test
  void aDisabledProviderIsReportedWithoutInvokingTheProbe() {
    final AtomicInteger probes = new AtomicInteger();
    final TestProviderConnectionService service =
        service(
            new SteppingClock(10),
            List.of(new FakeSender(BREVO, Optional.of("missing api key"))),
            new FakeProbe(
                BREVO,
                Mono.fromSupplier(
                    () -> {
                      probes.incrementAndGet();
                      return ProbeOutcome.ok();
                    })));

    StepVerifier.create(service.test(EMAIL, BREVO))
        .assertNext(result -> assertEquals(ProbeErrorCode.CREDENTIALS_MISSING, result.errorCode()))
        .verifyComplete();
    assertEquals(0, probes.get());
  }

  @Test
  void aCatalogProviderWithoutAProbeIsReportedAsMissingAdapter() {
    final TestProviderConnectionService service =
        service(new SteppingClock(10), List.of(enabled(BREVO)));

    StepVerifier.create(service.test(EMAIL, BREVO))
        .assertNext(result -> assertEquals(ProbeErrorCode.ADAPTER_MISSING, result.errorCode()))
        .verifyComplete();
  }

  @Test
  void aProbeThatDoesNotAnswerWithinTheLimitIsReportedAsTimeout() {
    StepVerifier.withVirtualTime(
            () ->
                service(
                        new SteppingClock(1),
                        List.of(enabled(BREVO)),
                        new FakeProbe(BREVO, Mono.never()))
                    .test(EMAIL, BREVO))
        .expectSubscription()
        .thenAwait(LIMIT)
        .assertNext(result -> assertEquals(ProbeErrorCode.TIMEOUT, result.errorCode()))
        .verifyComplete();
  }

  @Test
  void anUnexpectedProbeErrorIsReportedAsProviderErrorWithoutItsMessage() {
    final TestProviderConnectionService service =
        service(
            new SteppingClock(1),
            List.of(enabled(BREVO)),
            new FakeProbe(BREVO, Mono.error(new IllegalStateException("secret-token"))));

    StepVerifier.create(service.test(EMAIL, BREVO))
        .assertNext(
            result -> {
              assertEquals(ProbeErrorCode.PROVIDER_ERROR, result.errorCode());
              assertTrue(!result.toString().contains("secret-token"));
            })
        .verifyComplete();
  }

  @Test
  void aProbeThatCompletesEmptyIsReportedAsProviderError() {
    final TestProviderConnectionService service =
        service(new SteppingClock(1), List.of(enabled(BREVO)), new FakeProbe(BREVO, Mono.empty()));

    StepVerifier.create(service.test(EMAIL, BREVO))
        .assertNext(result -> assertEquals(ProbeErrorCode.PROVIDER_ERROR, result.errorCode()))
        .verifyComplete();
  }

  @Test
  void anUnknownChannelIsRejectedWithoutProbing() {
    final TestProviderConnectionService service =
        service(
            new SteppingClock(1),
            List.of(enabled(BREVO)),
            new FakeProbe(BREVO, Mono.just(ProbeOutcome.ok())));

    StepVerifier.create(service.test(ChannelType.of("FAX"), BREVO))
        .expectError(ChannelNotAvailableException.class)
        .verify();
  }

  @Test
  void aProviderOutsideTheChannelCatalogIsRejectedWithoutProbing() {
    final TestProviderConnectionService service =
        service(
            new SteppingClock(1),
            List.of(enabled(BREVO), enabled(SIMULATED)),
            new FakeProbe(BREVO, Mono.just(ProbeOutcome.ok())));

    StepVerifier.create(service.test(SMS, BREVO))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void onlyOneTestPerChannelAndProviderRunsAtATimeAndTheSlotIsReleasedAfterwards() {
    final Sinks.One<ProbeOutcome> gate = Sinks.one();
    final TestProviderConnectionService service =
        service(
            new SteppingClock(1),
            List.of(enabled(SIMULATED)),
            new FakeProbe(SIMULATED, gate.asMono()));

    final Mono<ProviderProbeResult> first = service.test(EMAIL, SIMULATED).cache();
    first.subscribe();

    StepVerifier.create(service.test(EMAIL, SIMULATED))
        .expectError(ProviderTestInProgressException.class)
        .verify();

    final Mono<ProviderProbeResult> otherChannel = service.test(SMS, SIMULATED).cache();
    otherChannel.subscribe();

    gate.tryEmitValue(ProbeOutcome.ok());
    StepVerifier.create(first).assertNext(result -> assertTrue(result.isOk())).verifyComplete();
    StepVerifier.create(otherChannel)
        .assertNext(result -> assertTrue(result.isOk()))
        .verifyComplete();
    StepVerifier.create(service.test(EMAIL, SIMULATED))
        .assertNext(result -> assertTrue(result.isOk()))
        .verifyComplete();
  }

  @Test
  void aCancelledTestReleasesItsSlot() {
    final TestProviderConnectionService service =
        service(
            new SteppingClock(1),
            List.of(enabled(SIMULATED)),
            new FakeProbe(SIMULATED, Mono.never()));

    service.test(EMAIL, SIMULATED).subscribe().dispose();

    StepVerifier.create(service.test(SMS, SIMULATED).timeout(Duration.ofMillis(50)))
        .expectError()
        .verify();
    StepVerifier.create(service.test(EMAIL, SIMULATED).timeout(Duration.ofMillis(50)))
        .expectErrorMatches(error -> !(error instanceof ProviderTestInProgressException))
        .verify();
  }
}
