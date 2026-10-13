package co.edu.uco.notification.infrastructure.adapter.out.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BrevoResponseClassifierTest {

  @ParameterizedTest
  @CsvSource({
    "201, ACCEPTED",
    "202, ACCEPTED",
    "200, ACCEPTED",
    "204, ACCEPTED",
    "301, RECOVERABLE_FAILURE",
    "400, PERMANENT_FAILURE",
    "401, PERMANENT_FAILURE",
    "403, PERMANENT_FAILURE",
    "402, RECOVERABLE_FAILURE",
    "404, PERMANENT_FAILURE",
    "408, RECOVERABLE_FAILURE",
    "429, RECOVERABLE_FAILURE",
    "422, PERMANENT_FAILURE",
    "500, RECOVERABLE_FAILURE",
    "503, RECOVERABLE_FAILURE"
  })
  void classifiesEachStatusCodeFamily(final int statusCode, final AttemptResult expected) {
    assertEquals(expected, BrevoResponseClassifier.classifyStatus(statusCode));
  }

  @Test
  void classifiesClientTimeoutAsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        BrevoResponseClassifier.classifyError(new TimeoutException("no response")));
  }

  @Test
  void classifiesConnectionErrorAsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        BrevoResponseClassifier.classifyError(new ConnectException("refused")));
  }

  @Test
  void classifiesAnyOtherExceptionAsRecoverable() {
    assertEquals(
        AttemptResult.RECOVERABLE_FAILURE,
        BrevoResponseClassifier.classifyError(new IllegalStateException("unexpected")));
  }
}
