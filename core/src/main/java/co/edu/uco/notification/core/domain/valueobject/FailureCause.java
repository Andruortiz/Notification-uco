package co.edu.uco.notification.core.domain.valueobject;

public enum FailureCause {
  TRANSPORT_ERROR,
  PROVIDER_ERROR,
  PROVIDER_REJECTED,
  PROVIDER_TEMPORARY,
  ATTACHMENTS_UNSUPPORTED,
  RETRIES_EXHAUSTED,
  INVALID_RECIPIENT,
  UNKNOWN
}
