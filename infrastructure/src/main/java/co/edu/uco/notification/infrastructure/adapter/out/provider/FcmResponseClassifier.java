package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;

public final class FcmResponseClassifier {

  private static final int REQUEST_TIMEOUT = 408;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int GONE = 410;
  private static final String UNREGISTERED = "UNREGISTERED";

  private FcmResponseClassifier() {}

  public static AttemptResult classify(final int statusCode, final String errorCode) {
    if (statusCode == GONE || (statusCode >= 400 && UNREGISTERED.equals(errorCode))) {
      return AttemptResult.INVALID_RECIPIENT;
    }
    return classifyStatus(statusCode);
  }

  public static AttemptResult classifyStatus(final int statusCode) {
    if (statusCode >= 200 && statusCode < 300) {
      return AttemptResult.ACCEPTED;
    }
    if (statusCode == REQUEST_TIMEOUT || statusCode == TOO_MANY_REQUESTS) {
      return AttemptResult.RECOVERABLE_FAILURE;
    }
    if (statusCode >= 400 && statusCode < 500) {
      return AttemptResult.PERMANENT_FAILURE;
    }
    return AttemptResult.RECOVERABLE_FAILURE;
  }

  public static AttemptResult classifyError(final Throwable error) {
    return AttemptResult.RECOVERABLE_FAILURE;
  }
}
