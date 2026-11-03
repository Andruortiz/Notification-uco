package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.utils.Preconditions;

public record ProbeOutcome(ProbeErrorCode errorCode) {

  private static final ProbeOutcome OK = new ProbeOutcome(null);

  public static ProbeOutcome ok() {
    return OK;
  }

  public static ProbeOutcome failed(final ProbeErrorCode errorCode) {
    return new ProbeOutcome(Preconditions.requireNonNull(errorCode, "errorCode must not be null"));
  }

  public boolean isOk() {
    return errorCode == null;
  }
}
