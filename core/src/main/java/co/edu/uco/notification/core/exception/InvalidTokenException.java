package co.edu.uco.notification.core.exception;

public class InvalidTokenException extends RuntimeException {

  public enum Reason {
    MALFORMED,
    INVALID_SIGNATURE,
    EXPIRED,
    NOT_YET_VALID,
    MISSING_EXPIRATION,
    MISSING_CLAIMS,
    UNKNOWN_ROLE
  }

  private final Reason reason;
  private final String tenantId;

  public InvalidTokenException(final String message) {
    this(message, Reason.MALFORMED, null, null);
  }

  public InvalidTokenException(final String message, final Throwable cause) {
    this(message, Reason.MALFORMED, null, cause);
  }

  public InvalidTokenException(
      final String message, final Reason reason, final String tenantId, final Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.tenantId = tenantId;
  }

  public Reason reason() {
    return reason;
  }

  public String tenantId() {
    return tenantId;
  }
}
