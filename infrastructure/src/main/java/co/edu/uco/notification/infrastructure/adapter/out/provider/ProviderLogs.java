package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.Notification;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.LogSanitizer;
import org.slf4j.Logger;

final class ProviderLogs {

  private ProviderLogs() {}

  static void disabled(final Logger logger, final ProviderId providerId, final String reason) {
    logger.warn(
        LogFields.fields("providerId", providerId.value(), "reason", LogSanitizer.safe(reason)),
        "Notification sender disabled");
  }

  static void rejectedBeforeCall(
      final Logger logger,
      final Notification notification,
      final ProviderId providerId,
      final String reason) {
    try (LogContext ignored = LogContext.of(notification)) {
      logger.info(
          LogFields.failure(
              ErrorCode.PROVIDER_REJECTED_BEFORE_CALL,
              "providerId",
              providerId.value(),
              "reason",
              reason),
          "Notification rejected before calling provider");
    }
  }

  static void dispatched(
      final Logger logger,
      final Notification notification,
      final ProviderId providerId,
      final AttemptResult result,
      final Integer httpStatus,
      final String providerMessageId) {
    try (LogContext ignored = LogContext.of(notification)) {
      logger.info(
          LogFields.fields(
              "providerId",
              providerId.value(),
              "result",
              result,
              "httpStatus",
              httpStatus,
              "providerMessageId",
              LogSanitizer.safe(providerMessageId),
              "recipient",
              LogSanitizer.maskRecipient(notification.recipient().address())),
          "Notification dispatched");
    }
  }

  static void dispatchedWithoutDetails(
      final Logger logger,
      final Notification notification,
      final ProviderId providerId,
      final AttemptResult result) {
    try (LogContext ignored = LogContext.of(notification)) {
      logger.info(
          LogFields.fields("providerId", providerId.value(), "result", result),
          "Notification dispatched");
    }
  }

  static void rejectedByProvider(
      final Logger logger,
      final Notification notification,
      final ProviderId providerId,
      final AttemptResult result,
      final int httpStatus,
      final String providerErrorCode) {
    try (LogContext ignored = LogContext.of(notification)) {
      logger.warn(
          LogFields.failure(
              codeOf(
                  result,
                  ErrorCode.PROVIDER_REJECTED_PERMANENT,
                  ErrorCode.PROVIDER_REJECTED_RECOVERABLE),
              "providerId",
              providerId.value(),
              "result",
              result,
              "httpStatus",
              httpStatus,
              "providerErrorCode",
              LogSanitizer.safe(providerErrorCode),
              "recipient",
              LogSanitizer.maskRecipient(notification.recipient().address())),
          "Notification rejected by provider");
    }
  }

  static void dispatchFailed(
      final Logger logger,
      final Notification notification,
      final ProviderId providerId,
      final AttemptResult result,
      final Throwable error) {
    try (LogContext ignored = LogContext.of(notification)) {
      logger.warn(
          LogFields.failure(
              codeOf(
                  result,
                  ErrorCode.PROVIDER_REJECTED_PERMANENT,
                  ErrorCode.PROVIDER_REJECTED_RECOVERABLE),
              "providerId",
              providerId.value(),
              "result",
              result,
              "errorType",
              error.getClass().getSimpleName()),
          "Notification dispatch failed");
    }
  }

  static void authorizationFailed(
      final Logger logger,
      final Notification notification,
      final ProviderId providerId,
      final AttemptResult result,
      final Integer httpStatus,
      final Throwable error) {
    try (LogContext ignored = LogContext.of(notification)) {
      logger.warn(
          LogFields.failure(
              codeOf(
                  result,
                  ErrorCode.PROVIDER_AUTHORIZATION_PERMANENT,
                  ErrorCode.PROVIDER_AUTHORIZATION_RECOVERABLE),
              "providerId",
              providerId.value(),
              "stage",
              "authorization",
              "result",
              result,
              "httpStatus",
              httpStatus,
              "errorType",
              error == null ? null : error.getClass().getSimpleName()),
          "Notification authorization failed");
    }
  }

  private static ErrorCode codeOf(
      final AttemptResult result, final ErrorCode permanent, final ErrorCode recoverable) {
    return result == AttemptResult.PERMANENT_FAILURE ? permanent : recoverable;
  }
}
