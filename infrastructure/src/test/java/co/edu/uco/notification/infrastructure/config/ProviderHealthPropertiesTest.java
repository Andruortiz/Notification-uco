package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProviderHealthPropertiesTest {

  private static ProviderHealthProperties of(
      final Duration window,
      final Duration lookback,
      final Duration timeout,
      final Long minSamples,
      final Double healthy) {
    return new ProviderHealthProperties(
        null, null, window, lookback, timeout, minSamples, healthy, null, null);
  }

  @Test
  void defaultsAreValid() {
    assertDoesNotThrow(() -> of(null, null, null, null, null));
  }

  @Test
  void rejectsAbsurdValues() {
    assertThrows(IllegalArgumentException.class, () -> of(Duration.ZERO, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> of(null, Duration.ofSeconds(59), null, null, null));
    assertThrows(IllegalArgumentException.class, () -> of(null, null, Duration.ZERO, null, null));
    assertThrows(IllegalArgumentException.class, () -> of(null, null, null, -1L, null));
    assertThrows(IllegalArgumentException.class, () -> of(null, null, null, null, 1.5));
    assertThrows(IllegalArgumentException.class, () -> of(null, null, null, null, -0.1));
  }
}
