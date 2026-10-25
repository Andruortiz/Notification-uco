package co.edu.uco.notification.core.domain.valueobject;

import java.time.Duration;

public record ProviderHealthSettings(
    Duration window,
    Duration lookback,
    long minSamples,
    double healthyThreshold,
    double downThreshold,
    double recoverableThreshold,
    String grafanaUrl) {}
