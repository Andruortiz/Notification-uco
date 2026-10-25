package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "recipient_identities")
@CompoundIndex(
    name = "tenant_subject_unique",
    def = "{'tenantId': 1, 'subject': 1}",
    unique = true,
    partialFilter = "{'subject': {'$type': 'string'}}")
public record RecipientIdentityDocument(
    @Id String recipientIdentityId, String tenantId, String subject, Instant createdAt) {}
