package co.edu.uco.notification.core.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelRouteTest {

  @Test
  void preservesGivenValues() {
    final ChannelRoute route =
        new ChannelRoute(
            ChannelType.of("EMAIL"),
            List.of(ProviderId.of("brevo"), ProviderId.of("sendgrid")),
            "{\"type\":\"object\"}");

    assertEquals(ChannelType.of("EMAIL"), route.channelType());
    assertEquals(List.of(ProviderId.of("brevo"), ProviderId.of("sendgrid")), route.providers());
    assertEquals("{\"type\":\"object\"}", route.contentSchema());
  }

  @Test
  void preferredProviderIsFirstInOrder() {
    final ChannelRoute route =
        new ChannelRoute(
            ChannelType.of("EMAIL"),
            List.of(ProviderId.of("brevo"), ProviderId.of("sendgrid")),
            null);

    assertEquals(ProviderId.of("brevo"), route.preferredProvider());
  }

  @Test
  void allowsNullContentSchema() {
    final ChannelRoute route =
        new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), null);

    assertNull(route.contentSchema());
  }

  @Test
  void rejectsNullChannelType() {
    assertThrows(
        NullPointerException.class,
        () -> new ChannelRoute(null, List.of(ProviderId.of("brevo")), null));
  }

  @Test
  void rejectsNullProviders() {
    assertThrows(
        NullPointerException.class, () -> new ChannelRoute(ChannelType.of("EMAIL"), null, null));
  }

  @Test
  void rejectsEmptyProviders() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChannelRoute(ChannelType.of("EMAIL"), List.of(), null));
  }
}
