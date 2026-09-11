package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.valueobject.AttemptOrigin;
import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import java.time.Instant;

public record DeliveryAttemptDocument(
    Instant occurredOn, AttemptResult result, AttemptOrigin origin, String providerId) {}
