package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "devices")
@CompoundIndex(
    name = "tenant_fingerprint_unique",
    def = "{'tenantId': 1, 'tokenFingerprint': 1}",
    unique = true)
@CompoundIndex(name = "tenant_identity", def = "{'tenantId': 1, 'recipientIdentityId': 1}")
public record DeviceDocument(
    @Id String deviceId,
    String tenantId,
    String recipientIdentityId,
    String platform,
    String protectedToken,
    String tokenFingerprint,
    Integer keyVersion,
    Integer fingerprintKeyVersion,
    String originComponent,
    Instant createdAt,
    Instant lastSeenAt) {}
