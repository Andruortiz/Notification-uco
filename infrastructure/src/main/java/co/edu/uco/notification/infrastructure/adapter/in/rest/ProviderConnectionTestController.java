package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.valueobject.AuthenticatedPrincipal;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.port.in.ProviderProbeResult;
import co.edu.uco.notification.core.port.in.TestProviderConnectionUseCase;
import co.edu.uco.notification.infrastructure.config.CorrelationContext;
import co.edu.uco.notification.infrastructure.config.LogContext;
import co.edu.uco.notification.infrastructure.config.LogFields;
import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.LogSanitizer;
import co.edu.uco.notification.utils.Preconditions;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ProviderConnectionTestController {

  static final String METRIC_NAME = "notification.provider.probe";

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ProviderConnectionTestController.class);

  private final TestProviderConnectionUseCase testProviderConnectionUseCase;
  private final MeterRegistry meterRegistry;

  public ProviderConnectionTestController(
      final TestProviderConnectionUseCase testProviderConnectionUseCase,
      final MeterRegistry meterRegistry) {
    this.testProviderConnectionUseCase =
        Preconditions.requireNonNull(
            testProviderConnectionUseCase, "testProviderConnectionUseCase must not be null");
    this.meterRegistry =
        Preconditions.requireNonNull(meterRegistry, "meterRegistry must not be null");
  }

  @PostMapping("/provider-connection-tests")
  public Mono<ProviderConnectionTestResponse> test(
      final AuthenticatedPrincipal principal,
      @RequestBody final ProviderConnectionTestRequest request) {
    return Mono.deferContextual(
        context -> {
          final ChannelType channel = ChannelType.of(request.channel());
          final ProviderId providerId = ProviderId.of(request.providerId());
          final CorrelationId correlationId = CorrelationContext.from(context);
          return testProviderConnectionUseCase
              .test(channel, providerId)
              .doOnNext(result -> record(principal, correlationId, result))
              .map(ProviderConnectionTestResponse::from);
        });
  }

  private void record(
      final AuthenticatedPrincipal principal,
      final CorrelationId correlationId,
      final ProviderProbeResult result) {
    final String status = result.isOk() ? "OK" : "FAILED";
    isolated(
        () ->
            meterRegistry
                .counter(METRIC_NAME, "providerId", result.providerId().value(), "status", status)
                .increment());
    isolated(() -> log(principal, correlationId, result, status));
  }

  private static boolean isolated(final Runnable telemetry) {
    try {
      telemetry.run();
      return true;
    } catch (final RuntimeException telemetryFailure) {
      return false;
    }
  }

  private static void log(
      final AuthenticatedPrincipal principal,
      final CorrelationId correlationId,
      final ProviderProbeResult result,
      final String status) {
    try (LogContext ignored =
        LogContext.open(correlationId, LogSanitizer.safe(principal.tenantId().value()), null)) {
      LOGGER.info(
          LogFields.fields(
              "providerId",
              result.providerId().value(),
              "channel",
              result.channel().value(),
              "status",
              status,
              "errorCode",
              result.errorCode() == null ? null : result.errorCode().name(),
              "latencyMs",
              result.latencyMs(),
              "requestedBy",
              LogSanitizer.safe(principal.subject())),
          "Provider connection test finished");
    }
  }
}
