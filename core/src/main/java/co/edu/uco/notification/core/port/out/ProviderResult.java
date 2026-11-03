package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.FailureCause;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.Preconditions;

public record ProviderResult(
    AttemptResult result,
    ProviderId providerId,
    Long latencyMillis,
    String messageId,
    FailureCause cause) {

  public ProviderResult {
    Preconditions.requireNonNull(result, "result must not be null");
  }

  public static ProviderResult of(final AttemptResult result, final ProviderId providerId) {
    return new ProviderResult(result, providerId, null, null, null);
  }

  public static ProviderResult of(final AttemptResult result) {
    return new ProviderResult(result, null, null, null, null);
  }
}
