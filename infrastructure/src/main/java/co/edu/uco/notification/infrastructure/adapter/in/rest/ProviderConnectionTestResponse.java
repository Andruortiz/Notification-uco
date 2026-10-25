package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.port.in.ProviderProbeResult;

public record ProviderConnectionTestResponse(
    String providerId, String channel, String status, long latencyMs, String errorCode) {

  static ProviderConnectionTestResponse from(final ProviderProbeResult result) {
    return new ProviderConnectionTestResponse(
        result.providerId().value(),
        result.channel().value(),
        result.isOk() ? "OK" : "FAILED",
        result.latencyMs(),
        result.errorCode() == null ? null : result.errorCode().name());
  }
}
