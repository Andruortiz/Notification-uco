package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.FailureCause;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.out.ProviderResult;
import co.edu.uco.notification.utils.Preconditions;

public record AttemptRecord(
    AttemptResult result,
    AttemptOrigin origin,
    ProviderId providerId,
    boolean failedOver,
    Long latencyMillis,
    FailureCause failureCause,
    String correlationId,
    String messageId) {

  public AttemptRecord {
    Preconditions.requireNonNull(origin, "origin must not be null");
    Preconditions.requireNonNull(providerId, "providerId must not be null");
  }

  public static AttemptRecord of(
      final AttemptResult result, final AttemptOrigin origin, final ProviderId providerId) {
    return new AttemptRecord(result, origin, providerId, false, null, null, null, null);
  }

  public static AttemptRecord from(
      final ProviderResult providerResult,
      final AttemptOrigin origin,
      final ProviderId providerId) {
    return new AttemptRecord(
        providerResult.result(),
        origin,
        providerId,
        false,
        providerResult.latencyMillis(),
        providerResult.cause(),
        null,
        providerResult.messageId());
  }

  AttemptRecord withResult(final AttemptResult newResult) {
    return new AttemptRecord(
        newResult,
        origin,
        providerId,
        failedOver,
        latencyMillis,
        failureCause,
        correlationId,
        messageId);
  }

  AttemptRecord asFailedOver() {
    return new AttemptRecord(
        result, origin, providerId, true, latencyMillis, failureCause, correlationId, messageId);
  }
}
