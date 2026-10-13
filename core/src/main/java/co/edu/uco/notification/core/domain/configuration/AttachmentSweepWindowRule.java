package co.edu.uco.notification.core.domain.configuration;

import java.util.Map;
import java.util.Optional;

public final class AttachmentSweepWindowRule implements CrossParameterRule {

  @Override
  public String name() {
    return "AttachmentSweepWindowRule";
  }

  @Override
  public Optional<String> violation(
      final Map<String, Long> values, final FixedConfiguration fixed) {
    final long totalScanWait = fixed.attachmentScanTimeoutMs() * fixed.attachmentScanMaxAttempts();
    if (fixed.attachmentSweepWindowMs() > totalScanWait) {
      return Optional.empty();
    }
    return Optional.of(
        "attachment sweep window "
            + fixed.attachmentSweepWindowMs()
            + " ms must exceed scan timeout times max attempts ("
            + totalScanWait
            + " ms)");
  }
}
