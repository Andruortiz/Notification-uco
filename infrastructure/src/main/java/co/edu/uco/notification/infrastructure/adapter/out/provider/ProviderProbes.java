package co.edu.uco.notification.infrastructure.adapter.out.provider;

import co.edu.uco.notification.core.domain.valueobject.ProbeErrorCode;
import co.edu.uco.notification.core.port.out.ProbeOutcome;
import java.io.IOException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

final class ProviderProbes {

  private ProviderProbes() {}

  static Mono<ProbeOutcome> classify(final Mono<Integer> status) {
    return status
        .map(ProviderProbes::ofStatus)
        .defaultIfEmpty(ProbeOutcome.failed(ProbeErrorCode.PROVIDER_ERROR))
        .onErrorResume(error -> Mono.just(ofError(error)));
  }

  static ProbeOutcome ofStatus(final int status) {
    if (status >= 200 && status < 300) {
      return ProbeOutcome.ok();
    }
    if (status == 401 || status == 403) {
      return ProbeOutcome.failed(ProbeErrorCode.AUTH_REJECTED);
    }
    return ProbeOutcome.failed(ProbeErrorCode.PROVIDER_ERROR);
  }

  static ProbeOutcome ofError(final Throwable error) {
    return error instanceof WebClientRequestException || error instanceof IOException
        ? ProbeOutcome.failed(ProbeErrorCode.UNREACHABLE)
        : ProbeOutcome.failed(ProbeErrorCode.PROVIDER_ERROR);
  }
}
