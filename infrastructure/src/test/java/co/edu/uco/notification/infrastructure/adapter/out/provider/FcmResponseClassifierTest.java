package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FcmResponseClassifierTest {

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 204, 299})
  void successfulStatusesAreAccepted(final int status) {
    assertEquals(AttemptResult.ACCEPTED, FcmResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 301, 302, 399})
  void redirectsAreRecoverable(final int status) {
    assertEquals(AttemptResult.RECOVERABLE_FAILURE, FcmResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404})
  void invalidArgumentCredentialsSenderMismatchAndUnregisteredArePermanent(final int status) {
    assertEquals(AttemptResult.PERMANENT_FAILURE, FcmResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {402, 405, 409, 413, 422, 499})
  void otherClientRejectionsArePermanent(final int status) {
    assertEquals(AttemptResult.PERMANENT_FAILURE, FcmResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429})
  void requestTimeoutAndQuotaExceededAreRecoverable(final int status) {
    assertEquals(AttemptResult.RECOVERABLE_FAILURE, FcmResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504, 599})
  void internalAndUnavailableAreRecoverable(final int status) {
    assertEquals(AttemptResult.RECOVERABLE_FAILURE, FcmResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {100, 199, 600})
  void statusesOutsideTheKnownFamiliesAreRecoverable(final int status) {
    assertEquals(AttemptResult.RECOVERABLE_FAILURE, FcmResponseClassifier.classifyStatus(status));
  }

  @Test
  void clientTimeoutIsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        FcmResponseClassifier.classifyError(ReadTimeoutException.INSTANCE));
  }

  @Test
  void connectionErrorIsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        FcmResponseClassifier.classifyError(new ConnectException("refused")));
  }

  @Test
  void anyOtherErrorIsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        FcmResponseClassifier.classifyError(new IllegalStateException("unexpected")));
  }
}
