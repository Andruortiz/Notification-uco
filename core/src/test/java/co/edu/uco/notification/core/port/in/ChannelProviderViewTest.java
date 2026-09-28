package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import org.junit.jupiter.api.Test;

class ChannelProviderViewTest {

  private static final ProviderId PROVIDER = ProviderId.of("brevo");

  @Test
  void acceptsAReasonOnlyWhenTheProviderIsNotEnabled() {
    assertDoesNotThrow(() -> new ChannelProviderView(PROVIDER, 1, ProviderStatus.ENABLED, null));
    assertDoesNotThrow(
        () -> new ChannelProviderView(PROVIDER, 1, ProviderStatus.DISABLED, "missing key"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChannelProviderView(PROVIDER, 1, ProviderStatus.ENABLED, "unexpected"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChannelProviderView(PROVIDER, 1, ProviderStatus.MISSING_ADAPTER, null));
  }

  @Test
  void rejectsAPreferenceOrderBelowOne() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChannelProviderView(PROVIDER, 0, ProviderStatus.ENABLED, null));
  }

  @Test
  void rejectsNullProviderAndStatus() {
    assertThrows(
        NullPointerException.class,
        () -> new ChannelProviderView(null, 1, ProviderStatus.ENABLED, null));
    assertThrows(
        NullPointerException.class, () -> new ChannelProviderView(PROVIDER, 1, null, null));
  }
}
