package co.edu.uco.notification.core.domain.valueobject;

public record ProviderHealth(
    String provider,
    String channel,
    ProviderHealthStatus status,
    Double successRate,
    long sampleCount,
    Long latencyP95Millis,
    ProviderLastError lastError) {}
