package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

class CorrelationContextConfigTest {

  @BeforeAll
  static void registerAccessors() {
    new CorrelationContextConfig().propagateLogFieldsToTheMdc();
  }

  private static Mono<String> readAfterThreadHops(final String id) {
    return Mono.just(id)
        .publishOn(Schedulers.parallel())
        .map(ignored -> String.valueOf(MDC.get(LogFields.CORRELATION_ID)))
        .publishOn(Schedulers.boundedElastic())
        .map(first -> first + "#" + MDC.get(LogFields.CORRELATION_ID))
        .contextWrite(Context.of(LogFields.CORRELATION_ID, id));
  }

  @Test
  void theReactorContextValueReachesTheMdcAfterThreadHops() {
    final String seen = readAfterThreadHops("corr-hop").block(Duration.ofSeconds(5));

    assertEquals("corr-hop#corr-hop", seen);
  }

  @Test
  void tenantNotificationAndTraceparentAreAlsoBridgedToTheMdc() {
    final String trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    final String seen =
        Mono.just("x")
            .publishOn(Schedulers.parallel())
            .map(
                ignored ->
                    MDC.get(LogFields.TENANT_ID)
                        + "#"
                        + MDC.get(LogFields.NOTIFICATION_ID)
                        + "#"
                        + MDC.get(LogFields.TRACE_PARENT))
            .contextWrite(
                Context.of(
                    LogFields.TENANT_ID,
                    "tenant-a",
                    LogFields.NOTIFICATION_ID,
                    "n-1",
                    LogFields.TRACE_PARENT,
                    trace))
            .block(Duration.ofSeconds(5));

    assertEquals("tenant-a#n-1#" + trace, seen);
  }

  @Test
  void concurrentRequestsNeverObserveEachOthersCorrelationId() {
    final int requests = 200;

    final List<String> results =
        Flux.range(0, requests)
            .flatMap(
                index ->
                    readAfterThreadHops("corr-" + index).map(seen -> "corr-" + index + "#" + seen),
                64)
            .collectList()
            .block(Duration.ofSeconds(20));

    assertEquals(requests, results.size());
    results.forEach(
        result -> {
          final String[] parts = result.split("#");
          assertEquals(3, parts.length);
          assertEquals(parts[0], parts[1]);
          assertEquals(parts[0], parts[2]);
        });
  }

  @Test
  void afterTheFlowCompletesNoCorrelationIdLeaksIntoReusedThreads() throws Exception {
    readAfterThreadHops("corr-leak").block(Duration.ofSeconds(5));

    final List<String> leaked = new ArrayList<>();
    final int probes = 16;
    final CountDownLatch done = new CountDownLatch(probes);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    for (int i = 0; i < probes; i++) {
      Schedulers.parallel()
          .schedule(
              () -> {
                try {
                  final String value = MDC.get(LogFields.CORRELATION_ID);
                  if (value != null) {
                    synchronized (leaked) {
                      leaked.add(value);
                    }
                  }
                } catch (final RuntimeException e) {
                  failure.set(e);
                } finally {
                  done.countDown();
                }
              });
    }

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertNull(failure.get());
    assertTrue(leaked.isEmpty(), "leaked ids: " + leaked);
    assertNull(MDC.get(LogFields.CORRELATION_ID));
  }
}
