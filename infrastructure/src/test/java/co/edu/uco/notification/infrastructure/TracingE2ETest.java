package co.edu.uco.notification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationStatus;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.port.in.RequeuePendingNotificationsUseCase;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.infrastructure.adapter.out.mongo.NotificationDocument;
import co.edu.uco.notification.infrastructure.support.InMemorySpans;
import co.edu.uco.notification.infrastructure.support.InMemoryTracingConfig;
import co.edu.uco.notification.infrastructure.support.LogCapture;
import co.edu.uco.notification.infrastructure.support.NotificationFlow;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "MONGO_USERNAME=test",
      "MONGO_PASSWORD=test",
      "notification.catalog.refresh-interval-ms=500",
      "notification.scheduler.requeue-interval-ms=600000",
      "notification.scheduler.pending-orphan-threshold-ms=1000"
    })
@Import(InMemoryTracingConfig.class)
@Testcontainers
class TracingE2ETest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management");

  private static final Duration LIMIT = Duration.ofSeconds(30);
  private static final String TENANT = "tenant-a";
  private static final String INVALID_TRACEPARENT = "invalid-trace-forged-4f2a9c";

  @LocalServerPort private int apiPort;

  @Autowired private InMemorySpans spans;
  @Autowired private ChannelCatalogPort channelCatalogPort;
  @Autowired private ReactiveMongoTemplate mongoTemplate;
  @Autowired private RequeuePendingNotificationsUseCase requeueUseCase;

  private NotificationFlow flow;
  private LogCapture logs;

  @BeforeEach
  void setUp() throws InterruptedException {
    flow = new NotificationFlow(NotificationFlow.client(apiPort, LIMIT));
    awaitRoute();
    logs = LogCapture.start();
  }

  @AfterEach
  void tearDown() {
    logs.close();
  }

  private void awaitRoute() throws InterruptedException {
    final Instant deadline = Instant.now().plus(LIMIT);
    while (Instant.now().isBefore(deadline)) {
      if (channelCatalogPort
          .findActiveRoute(ChannelType.of("EMAIL"), TenantId.of("readiness-check"))
          .blockOptional()
          .isPresent()) {
        return;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("channel EMAIL never became active");
  }

  private static String newTraceId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static String traceparent(final String traceId) {
    return "00-" + traceId + "-00f067aa0ba902b7-01";
  }

  private static String newCorrelation(final String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private String deliver(final String correlationId, final String traceparent) throws Exception {
    final String id = flow.accept(TENANT, "ext-" + UUID.randomUUID(), correlationId, traceparent);
    flow.awaitStatus(TENANT, id, "DELIVERED", LIMIT);
    return id;
  }

  private static void assertEveryLogCarriesTheTrace(
      final List<ILoggingEvent> events, final String traceId) {
    assertFalse(events.isEmpty(), "positive control: the journey must have written logs");
    for (final ILoggingEvent event : events) {
      assertEquals(
          traceId,
          event.getMDCPropertyMap().get("traceId"),
          "log without the journey trace: " + event.getFormattedMessage());
      assertTrue(event.getMDCPropertyMap().containsKey("spanId"));
    }
  }

  @Test
  void aValidTraceparentIsContinuedByEverySpanAndEveryLogOfTheJourney() throws Exception {
    final String traceId = newTraceId();
    final String correlationId = newCorrelation("t1");

    deliver(correlationId, traceparent(traceId));

    final List<SpanData> journey = spans.awaitFullJourney(correlationId, LIMIT);
    assertTrue(InMemorySpans.hasFullJourney(journey), "missing spans: " + names(journey));
    for (final SpanData span : journey) {
      assertEquals(traceId, span.getTraceId(), span.getName());
    }
    assertTrue(spans.ofTrace(traceId).size() >= journey.size());
    for (final SpanData span : spans.ofTrace(traceId)) {
      assertEquals(correlationId, InMemorySpans.correlationOf(span), span.getName());
    }
    assertNotEquals(correlationId, traceId);
    assertEveryLogCarriesTheTrace(logs.withCorrelation(correlationId), traceId);
  }

  @Test
  void withoutTraceparentTheServiceStartsOneTraceDistinctFromTheCorrelationId() throws Exception {
    final String correlationId = newCorrelation("t2");

    deliver(correlationId, null);

    final List<SpanData> journey = spans.awaitFullJourney(correlationId, LIMIT);
    assertTrue(InMemorySpans.hasFullJourney(journey), "missing spans: " + names(journey));
    final Set<String> traces =
        journey.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
    assertEquals(1, traces.size());
    final String traceId = traces.iterator().next();
    assertTrue(traceId.matches("[0-9a-f]{32}"));
    assertNotEquals(correlationId, traceId);
    final SpanData server =
        journey.stream()
            .filter(span -> span.getKind() == SpanKind.SERVER)
            .findFirst()
            .orElseThrow();
    assertFalse(SpanId.isValid(server.getParentSpanId()));
    assertEveryLogCarriesTheTrace(logs.withCorrelation(correlationId), traceId);
  }

  @Test
  void anInvalidTraceparentIsDiscardedAndNeverWrittenToLogsOrSpans() throws Exception {
    final String correlationId = newCorrelation("t3");

    deliver(correlationId, INVALID_TRACEPARENT);

    final List<SpanData> journey = spans.awaitFullJourney(correlationId, LIMIT);
    assertTrue(InMemorySpans.hasFullJourney(journey), "missing spans: " + names(journey));
    final Set<String> traces =
        journey.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
    assertEquals(1, traces.size());
    assertTrue(traces.iterator().next().matches("[0-9a-f]{32}"));
    assertFalse(logs.withCorrelation(correlationId).isEmpty(), "positive control: logs captured");
    for (final ILoggingEvent event : logs.events()) {
      assertFalse(event.getFormattedMessage().contains(INVALID_TRACEPARENT));
      assertFalse(event.getMDCPropertyMap().values().contains(INVALID_TRACEPARENT));
    }
    for (final SpanData span : spans.finished()) {
      assertFalse(
          span.getAttributes().asMap().values().stream()
              .anyMatch(value -> String.valueOf(value).contains(INVALID_TRACEPARENT)));
    }
  }

  @Test
  void concurrentRequestsKeepTheirOwnTraceAndNeverMix() throws Exception {
    final String traceA = newTraceId();
    final String traceB = newTraceId();
    final String correlationA = newCorrelation("t4a");
    final String correlationB = newCorrelation("t4b");

    final CompletableFuture<String> first =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return deliver(correlationA, traceparent(traceA));
              } catch (final Exception e) {
                throw new IllegalStateException(e);
              }
            });
    final CompletableFuture<String> second =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return deliver(correlationB, traceparent(traceB));
              } catch (final Exception e) {
                throw new IllegalStateException(e);
              }
            });
    first.get();
    second.get();

    final List<SpanData> journeyA = spans.awaitFullJourney(correlationA, LIMIT);
    final List<SpanData> journeyB = spans.awaitFullJourney(correlationB, LIMIT);
    assertTrue(InMemorySpans.hasFullJourney(journeyA));
    assertTrue(InMemorySpans.hasFullJourney(journeyB));
    journeyA.forEach(span -> assertEquals(traceA, span.getTraceId(), span.getName()));
    journeyB.forEach(span -> assertEquals(traceB, span.getTraceId(), span.getName()));
    assertEveryLogCarriesTheTrace(logs.withCorrelation(correlationA), traceA);
    assertEveryLogCarriesTheTrace(logs.withCorrelation(correlationB), traceB);
  }

  @Test
  void aSchedulerRequeueStartsItsOwnTraceAndKeepsThePersistedCorrelationId() throws Exception {
    final String traceId = newTraceId();
    final String correlationId = newCorrelation("t5");
    final String id = deliver(correlationId, traceparent(traceId));
    spans.awaitFullJourney(correlationId, LIMIT);
    mongoTemplate
        .updateFirst(
            Query.query(Criteria.where("_id").is(id)),
            new Update()
                .set("status", NotificationStatus.PENDING)
                .set("pendingSince", Instant.now().minusSeconds(120)),
            NotificationDocument.class)
        .block();

    requeueUseCase.requeuePending().block();

    final List<SpanData> all =
        spans.await(
            correlationId, found -> InMemorySpans.count(found, InMemorySpans.DISPATCH) >= 2, LIMIT);
    assertEquals(2, InMemorySpans.count(all, InMemorySpans.DISPATCH), names(all));
    final Set<String> traces = all.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
    assertEquals(2, traces.size(), names(all));
    assertTrue(traces.contains(traceId));
    final String requeueTrace =
        traces.stream().filter(trace -> !trace.equals(traceId)).findFirst().orElseThrow();
    final List<SpanData> requeued =
        all.stream().filter(span -> span.getTraceId().equals(requeueTrace)).toList();
    assertTrue(
        requeued.stream()
            .anyMatch(
                span ->
                    span.getKind() == SpanKind.PRODUCER && !SpanId.isValid(span.getParentSpanId())),
        "the requeue publication must be the root of its own trace");
    assertTrue(requeued.stream().anyMatch(span -> span.getName().equals(InMemorySpans.DISPATCH)));
    assertTrue(
        requeued.stream().anyMatch(span -> span.getName().equals(InMemorySpans.PROVIDER_CALL)));
    requeued.forEach(
        span -> assertEquals(correlationId, InMemorySpans.correlationOf(span), span.getName()));
  }

  private static String names(final List<SpanData> found) {
    return found.stream()
        .map(span -> span.getKind() + ":" + span.getName() + "@" + span.getTraceId())
        .collect(Collectors.joining(", "));
  }
}
