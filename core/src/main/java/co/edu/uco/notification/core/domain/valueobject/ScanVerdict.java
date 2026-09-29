package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;

public record ScanVerdict(Outcome outcome, String signature, String signatureVersion) {

  public enum Outcome {
    CLEAN,
    INFECTED
  }

  public ScanVerdict {
    Preconditions.requireNonNull(outcome, "outcome must not be null");
    Preconditions.requireNonNull(signatureVersion, "signatureVersion must not be null");
  }

  public static ScanVerdict clean(final String signatureVersion) {
    return new ScanVerdict(Outcome.CLEAN, null, signatureVersion);
  }

  public static ScanVerdict infected(final String signature, final String signatureVersion) {
    return new ScanVerdict(Outcome.INFECTED, signature, signatureVersion);
  }

  public boolean isInfected() {
    return outcome == Outcome.INFECTED;
  }
}
