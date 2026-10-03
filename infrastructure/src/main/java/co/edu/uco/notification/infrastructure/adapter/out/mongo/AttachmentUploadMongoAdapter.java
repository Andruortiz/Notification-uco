package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.ScanState;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import co.edu.uco.notification.core.repository.AttachmentUploadRepository;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AttachmentUploadMongoAdapter implements AttachmentUploadRepository {

  private final ReactiveMongoTemplate mongoTemplate;

  public AttachmentUploadMongoAdapter(final ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
  }

  @Override
  public Mono<AttachmentUpload> insert(final AttachmentUpload upload) {
    Preconditions.requireNonNull(upload, "upload must not be null");
    return mongoTemplate.insert(toDocument(upload, 0L)).map(AttachmentUploadMongoAdapter::toDomain);
  }

  @Override
  public Mono<AttachmentUpload> findByTenantAndId(
      final TenantId tenantId, final UploadId uploadId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(uploadId, "uploadId must not be null");
    return mongoTemplate
        .findOne(
            Query.query(
                Criteria.where("_id").is(uploadId.value()).and("tenantId").is(tenantId.value())),
            AttachmentUploadDocument.class)
        .map(AttachmentUploadMongoAdapter::toDomain);
  }

  @Override
  public Mono<Boolean> transition(final AttachmentUpload expected, final AttachmentUpload next) {
    Preconditions.requireNonNull(expected, "expected must not be null");
    Preconditions.requireNonNull(next, "next must not be null");
    final Query query =
        Query.query(
            Criteria.where("_id")
                .is(expected.uploadId().value())
                .and("tenantId")
                .is(expected.tenantId().value())
                .and("state")
                .is(expected.state().name())
                .and("version")
                .is(expected.version()));
    final Update update =
        new Update()
            .set("state", next.state().name())
            .set("cleanKey", next.cleanKey())
            .set("rejectionReason", name(next.rejectionReason()))
            .set("signature", next.signature())
            .set("sha256", next.sha256() == null ? null : next.sha256().hex())
            .set("completedAt", next.completedAt())
            .set("scannedAt", next.scannedAt())
            .inc("version", 1);
    return mongoTemplate
        .findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true),
            AttachmentUploadDocument.class)
        .map(document -> Boolean.TRUE)
        .defaultIfEmpty(Boolean.FALSE);
  }

  @Override
  public Flux<AttachmentUpload> findStalePending(
      final Instant expiredBefore, final Instant scanRequestedBefore, final int limit) {
    Preconditions.requireNonNull(expiredBefore, "expiredBefore must not be null");
    Preconditions.requireNonNull(scanRequestedBefore, "scanRequestedBefore must not be null");
    final Criteria stale =
        new Criteria()
            .orOperator(
                Criteria.where("completedAt").is(null).and("expiresAt").lt(expiredBefore),
                Criteria.where("completedAt").lt(scanRequestedBefore));
    return mongoTemplate
        .find(
            Query.query(
                    new Criteria()
                        .andOperator(
                            Criteria.where("state").is(ScanState.PENDING_SCAN.name()), stale))
                .limit(limit),
            AttachmentUploadDocument.class)
        .map(AttachmentUploadMongoAdapter::toDomain);
  }

  private static AttachmentUploadDocument toDocument(
      final AttachmentUpload upload, final Long version) {
    return new AttachmentUploadDocument(
        upload.uploadId().value(),
        upload.tenantId().value(),
        upload.fileName(),
        upload.contentType(),
        upload.sizeBytes(),
        upload.uploadKey(),
        upload.cleanKey(),
        upload.state().name(),
        name(upload.rejectionReason()),
        upload.signature(),
        upload.sha256() == null ? null : upload.sha256().hex(),
        upload.issuedAt(),
        upload.expiresAt(),
        upload.completedAt(),
        upload.scannedAt(),
        version);
  }

  private static AttachmentUpload toDomain(final AttachmentUploadDocument document) {
    return new AttachmentUpload(
        UploadId.of(document.id()),
        TenantId.of(document.tenantId()),
        document.fileName(),
        document.contentType(),
        document.sizeBytes(),
        document.uploadKey(),
        document.cleanKey(),
        ScanState.valueOf(document.state()),
        document.rejectionReason() == null
            ? null
            : AttachmentRejectionReason.valueOf(document.rejectionReason()),
        document.signature(),
        document.sha256() == null ? null : Sha256Digest.fromHex(document.sha256()),
        document.issuedAt(),
        document.expiresAt(),
        document.completedAt(),
        document.scannedAt(),
        document.version());
  }

  private static String name(final Enum<?> value) {
    return value == null ? null : value.name();
  }
}
