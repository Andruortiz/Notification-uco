package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TwilioResponseClassifierTest {

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 202, 204, 299})
  void successfulStatusesAreAccepted(final int status) {
    assertEquals(AttemptResult.ACCEPTED, TwilioResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 301, 302, 399})
  void redirectsAreRecoverable(final int status) {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, TwilioResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 402, 405, 409, 422, 499})
  void clientRejectionsArePermanent(final int status) {
    assertEquals(AttemptResult.PERMANENT_FAILURE, TwilioResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429})
  void requestTimeoutAndRateLimitAreRecoverable(final int status) {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, TwilioResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504, 599})
  void serverErrorsAreRecoverable(final int status) {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, TwilioResponseClassifier.classifyStatus(status));
  }

  @ParameterizedTest
  @ValueSource(ints = {100, 199, 600})
  void statusesOutsideTheKnownFamiliesAreRecoverable(final int status) {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE, TwilioResponseClassifier.classifyStatus(status));
  }

  @Test
  void clientTimeoutIsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        TwilioResponseClassifier.classifyError(ReadTimeoutException.INSTANCE));
  }

  @Test
  void connectionErrorIsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        TwilioResponseClassifier.classifyError(new ConnectException("refused")));
  }

  @Test
  void anyOtherErrorIsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        TwilioResponseClassifier.classifyError(new IllegalStateException("unexpected")));
  }
}
