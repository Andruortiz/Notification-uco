package co.edu.uco.notification.infrastructure.adapter.in.web;

final class AuthenticationLogFormatter {

  private AuthenticationLogFormatter() {}

  static String rejection(final String tenantId, final RejectionReason reason) {
    return "tenantId=" + safe(tenantId) + " reason=" + reason;
  }

  static String safe(final String value) {
    if (value == null) {
      return "null";
    }
    final StringBuilder safe = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(code -> safe.appendCodePoint(Character.isISOControl(code) ? '_' : code));
    return safe.toString();
  }
}
