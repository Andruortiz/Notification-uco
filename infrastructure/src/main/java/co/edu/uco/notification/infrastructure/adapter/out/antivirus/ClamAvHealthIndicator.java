package co.edu.uco.notification.infrastructure.adapter.out.antivirus;

import co.edu.uco.notification.utils.Preconditions;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("clamavHealthIndicator")
public class ClamAvHealthIndicator implements ReactiveHealthIndicator {

  private final ClamAvMalwareScannerAdapter scanner;

  public ClamAvHealthIndicator(final ClamAvMalwareScannerAdapter scanner) {
    this.scanner = Preconditions.requireNonNull(scanner, "scanner must not be null");
  }

  @Override
  public Mono<Health> health() {
    return scanner.ping().map(up -> up ? Health.up().build() : Health.down().build());
  }
}
