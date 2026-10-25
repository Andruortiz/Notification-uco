package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "undeliverable_recipients")
@CompoundIndex(
    name = "tenant_channel_address_unique",
    def = "{'tenantId': 1, 'channelType': 1, 'normalizedAddress': 1}",
    unique = true)
public record UndeliverableRecipientDocument(
    @Id String id,
    String tenantId,
    String channelType,
    String normalizedAddress,
    String reason,
    Instant markedAt) {}
