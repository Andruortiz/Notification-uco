package co.edu.uco.notification.core.port.in;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderViewTest {

  private static final ProviderId PROVIDER = ProviderId.of("twilio");

  @Test
  void acceptsAReasonOnlyWhenTheProviderIsNotEnabled() {
    assertDoesNotThrow(() -> new ProviderView(PROVIDER, ProviderStatus.ENABLED, null, List.of()));
    assertDoesNotThrow(
        () -> new ProviderView(PROVIDER, ProviderStatus.DISABLED, "missing sid", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderView(PROVIDER, ProviderStatus.ENABLED, "unexpected", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderView(PROVIDER, ProviderStatus.DISABLED, null, List.of()));
  }

  @Test
  void rejectsNullFieldsAndAPreferenceOrderBelowOne() {
    assertThrows(
        NullPointerException.class,
        () -> new ProviderView(null, ProviderStatus.ENABLED, null, List.of()));
    assertThrows(
        NullPointerException.class, () -> new ProviderView(PROVIDER, null, null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ProviderView(PROVIDER, ProviderStatus.ENABLED, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new ProviderChannelView(ChannelType.of("SMS"), 0));
    assertThrows(NullPointerException.class, () -> new ProviderChannelView(null, 1));
  }
}
