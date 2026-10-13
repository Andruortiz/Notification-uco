package co.edu.uco.notification.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import org.junit.jupiter.api.Test;

class ProviderDisabledExceptionTest {

  @Test
  void messageIncludesProviderIdAndReason() {
    final ProviderDisabledException exception =
        new ProviderDisabledException(ProviderId.of("brevo"), "missing BREVO_API_KEY");

    assertEquals(
        "Notification sender disabled for provider brevo: missing BREVO_API_KEY",
        exception.getMessage());
  }
}
