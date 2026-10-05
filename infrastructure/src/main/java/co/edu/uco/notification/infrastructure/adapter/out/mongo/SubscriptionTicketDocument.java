package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "subscription_tickets")
public record SubscriptionTicketDocument(
    @Id String id,
    String tenantId,
    String role,
    String subject,
    @Indexed(name = "expires_at_ttl", expireAfterSeconds = 60) Instant expiresAt) {}
