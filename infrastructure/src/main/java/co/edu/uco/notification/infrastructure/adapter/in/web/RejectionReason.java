package co.edu.uco.notification.infrastructure.adapter.in.web;

public enum RejectionReason {
  MISSING_TOKEN,
  INVALID_TICKET,
  MALFORMED_TOKEN,
  INVALID_SIGNATURE,
  EXPIRED,
  NOT_YET_VALID,
  MISSING_EXPIRATION,
  MISSING_CLAIMS,
  UNKNOWN_ROLE,
  INSUFFICIENT_ROLE
}
