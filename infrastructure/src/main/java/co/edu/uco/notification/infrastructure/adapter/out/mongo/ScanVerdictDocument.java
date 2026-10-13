package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "attachment_scan_verdicts")
@CompoundIndex(name = "tenant_sha256_unique", def = "{'tenantId': 1, 'sha256': 1}", unique = true)
public record ScanVerdictDocument(
    @Id String id,
    String tenantId,
    String sha256,
    String outcome,
    String signature,
    String signatureVersion,
    Instant scannedAt,
    @Indexed(name = "expires_at_ttl", expireAfterSeconds = 0) Instant expiresAt) {}
