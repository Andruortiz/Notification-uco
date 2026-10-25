package co.edu.uco.notification.core.domain.valueobject;

import java.time.Instant;

public record ProviderLastError(String result, Instant at) {}
