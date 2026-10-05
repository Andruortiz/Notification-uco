package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConfigurationHolderTest {

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

  private static ConfigurationSnapshot snapshotOfVersion(final long version) {
    return new ConfigurationSnapshot(
        version,
        ConfigurationSource.PARAMETERS,
        Map.of("a", version, "b", version, "c", version),
        NOW,
        Set.of());
  }

  private static boolean coherent(final ConfigurationSnapshot snapshot) {
    return snapshot.values().values().stream().allMatch(value -> value == snapshot.version());
  }

  @Test
  void capturedSnapshotKeepsItsValuesAndVersionAcrossConcurrentReplacements() throws Exception {
    final ConfigurationHolder holder = new ConfigurationHolder(snapshotOfVersion(0));
    final ConfigurationSnapshot captured = holder.snapshot();
    final int replacements = 1_000;
    final int threads = 8;
    final ExecutorService executor = Executors.newFixedThreadPool(threads + 1);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicBoolean mixedVersionObserved = new AtomicBoolean(false);
    final AtomicBoolean stop = new AtomicBoolean(false);
    try {
      final Future<?> reader =
          executor.submit(
              () -> {
                await(start);
                while (!stop.get()) {
                  if (!coherent(holder.snapshot())) {
                    mixedVersionObserved.set(true);
                  }
                }
              });
      final Future<?>[] writers = new Future<?>[threads];
      for (int thread = 0; thread < threads; thread++) {
        final long offset = thread;
        writers[thread] =
            executor.submit(
                () -> {
                  await(start);
                  for (int index = 0; index < replacements / threads; index++) {
                    holder.replace(snapshotOfVersion(1L + offset * 1_000L + index));
                  }
                });
      }
      start.countDown();
      for (final Future<?> writer : writers) {
        writer.get(30, TimeUnit.SECONDS);
      }
      stop.set(true);
      reader.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertEquals(0, captured.version());
    assertEquals(Map.of("a", 0L, "b", 0L, "c", 0L), captured.values());
    assertTrue(coherent(captured));
    assertFalse(mixedVersionObserved.get());
    assertTrue(holder.snapshot().version() > 0);
    assertTrue(coherent(holder.snapshot()));
  }

  @Test
  void aNewCaptureAfterReplacementSeesTheNewVersion() {
    final ConfigurationHolder holder = new ConfigurationHolder(snapshotOfVersion(0));
    final ConfigurationSnapshot before = holder.snapshot();

    holder.replace(snapshotOfVersion(7));
    final ConfigurationSnapshot after = holder.snapshot();

    assertEquals(0, before.version());
    assertEquals(7, after.version());
    assertNotSame(before, after);
    assertSame(after, holder.snapshot());
  }

  @Test
  void compareAndSetOnlyReplacesWhenTheExpectedSnapshotIsStillCurrent() {
    final ConfigurationSnapshot initial = snapshotOfVersion(0);
    final ConfigurationHolder holder = new ConfigurationHolder(initial);
    final ConfigurationSnapshot next = snapshotOfVersion(1);

    assertTrue(holder.compareAndSet(initial, next));
    assertFalse(holder.compareAndSet(initial, snapshotOfVersion(2)));
    assertEquals(1, holder.snapshot().version());
  }

  private static void await(final CountDownLatch latch) {
    try {
      latch.await();
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
