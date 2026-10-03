package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "attachment_uploads")
@CompoundIndex(name = "tenant_upload", def = "{'tenantId': 1, '_id': 1}")
@CompoundIndex(name = "state_expires", def = "{'state': 1, 'expiresAt': 1}")
public record AttachmentUploadDocument(
    @Id String id,
    String tenantId,
    String fileName,
    String contentType,
    long sizeBytes,
    String uploadKey,
    String cleanKey,
    String state,
    String rejectionReason,
    String signature,
    String sha256,
    Instant issuedAt,
    Instant expiresAt,
    Instant completedAt,
    Instant scannedAt,
    Long version) {}
