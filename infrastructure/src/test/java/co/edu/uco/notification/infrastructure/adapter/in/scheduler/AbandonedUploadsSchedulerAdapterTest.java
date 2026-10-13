package co.edu.uco.notification.infrastructure.adapter.in.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.uco.notification.core.port.in.ExpireAbandonedUploadsUseCase;
import co.edu.uco.notification.infrastructure.config.LogLines;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

class AbandonedUploadsSchedulerAdapterTest {

  private final ExpireAbandonedUploadsUseCase useCase = mock(ExpireAbandonedUploadsUseCase.class);
  private final AbandonedUploadsSchedulerAdapter adapter =
      new AbandonedUploadsSchedulerAdapter(useCase);
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(AbandonedUploadsSchedulerAdapter.class)).addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(AbandonedUploadsSchedulerAdapter.class)).detachAppender(logs);
  }

  @Test
  void eachTickRunsTheSweepAndLogsHowManyUploadsFailed() {
    final AtomicInteger subscriptions = new AtomicInteger();
    when(useCase.expire())
        .thenReturn(Mono.just(3L).doOnSubscribe(s -> subscriptions.incrementAndGet()));

    adapter.expireAbandonedUploads();

    verify(useCase, times(1)).expire();
    assertEquals(1, subscriptions.get());
    assertEquals(
        1,
        logs.list.stream()
            .filter(event -> LogLines.render(event).contains("failedUploads=3"))
            .count());
  }

  @Test
  void aSweepWithNothingToFailLogsNothing() {
    when(useCase.expire()).thenReturn(Mono.just(0L));

    adapter.expireAbandonedUploads();

    assertEquals(0, logs.list.size());
  }

  @Test
  void aFailingSweepIsLoggedAndDoesNotPropagateSoTheNextTickRuns() {
    when(useCase.expire()).thenReturn(Mono.error(new IllegalStateException("mongo down")));

    adapter.expireAbandonedUploads();

    assertEquals(1, logs.list.size());
    assertEquals("Abandoned uploads sweep failed", logs.list.get(0).getFormattedMessage());
  }

  @Test
  void rejectsANullUseCase() {
    assertThrows(NullPointerException.class, () -> new AbandonedUploadsSchedulerAdapter(null));
  }
}
