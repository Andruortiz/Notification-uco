package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.AttachmentUpload;
import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.ScanState;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class AttachmentUploadMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private static final TenantId TENANT_A = TenantId.of("tenant-a");
  private static final TenantId TENANT_B = TenantId.of("tenant-b");
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
  private static final Sha256Digest SHA =
      Sha256Digest.of("content".getBytes(StandardCharsets.UTF_8));

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private AttachmentUploadMongoAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new AttachmentUploadMongoAdapter(mongoTemplate);
    mongoTemplate.dropCollection(AttachmentUploadDocument.class).block();
  }

  private static AttachmentUpload issued(final TenantId tenant) {
    return AttachmentUpload.issue(
        UploadId.newId(),
        tenant,
        "contract.pdf",
        "application/pdf",
        2_000_000L,
        NOW,
        NOW.plusSeconds(900));
  }

  @Test
  void insertThenFindByTenantAndIdRoundTrips() {
    final AttachmentUpload inserted = adapter.insert(issued(TENANT_A)).block();

    final AttachmentUpload found = adapter.findByTenantAndId(TENANT_A, inserted.uploadId()).block();

    assertEquals(inserted, found);
    assertEquals(0L, found.version());
    assertEquals(ScanState.PENDING_SCAN, found.state());
  }

  @Test
  void anotherTenantNeverFindsTheUpload() {
    final AttachmentUpload inserted = adapter.insert(issued(TENANT_A)).block();

    assertNull(adapter.findByTenantAndId(TENANT_B, inserted.uploadId()).block());
    assertEquals(
        inserted.uploadId(),
        adapter.findByTenantAndId(TENANT_A, inserted.uploadId()).block().uploadId());
  }

  @Test
  void transitionPersistsTheNewStateAndIncrementsTheVersion() {
    final AttachmentUpload inserted = adapter.insert(issued(TENANT_A)).block();
    final AttachmentUpload clean = inserted.markClean(SHA, NOW);

    assertTrue(adapter.transition(inserted, clean).block());

    final AttachmentUpload found = adapter.findByTenantAndId(TENANT_A, inserted.uploadId()).block();
    assertEquals(ScanState.CLEAN, found.state());
    assertEquals(SHA, found.sha256());
    assertEquals(clean.cleanKey(), found.cleanKey());
    assertEquals(1L, found.version());
  }

  @Test
  void onlyOneOfTwoConcurrentTransitionsFromTheSameStateWins() {
    final AttachmentUpload inserted = adapter.insert(issued(TENANT_A)).block();

    final List<Boolean> outcomes =
        Flux.merge(
                adapter.transition(inserted, inserted.markClean(SHA, NOW)),
                adapter.transition(
                    inserted,
                    inserted.markInfected(SHA, AttachmentRejectionReason.MALWARE, "Eicar", NOW)))
            .collectList()
            .block();

    assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count(), outcomes.toString());
  }

  @Test
  void aTransitionFromAStaleCopyDoesNothing() {
    final AttachmentUpload inserted = adapter.insert(issued(TENANT_A)).block();
    final AttachmentUpload completed = inserted.markCompleted(NOW);
    assertTrue(adapter.transition(inserted, completed).block());

    assertFalse(adapter.transition(inserted, inserted.markClean(SHA, NOW)).block());
    assertEquals(
        ScanState.PENDING_SCAN,
        adapter.findByTenantAndId(TENANT_A, inserted.uploadId()).block().state());
  }

  @Test
  void aTransitionAddressedToAnotherTenantDoesNothing() {
    final AttachmentUpload inserted = adapter.insert(issued(TENANT_A)).block();
    final AttachmentUpload forged =
        new AttachmentUpload(
            inserted.uploadId(),
            TENANT_B,
            inserted.fileName(),
            inserted.contentType(),
            inserted.sizeBytes(),
            inserted.uploadKey(),
            null,
            ScanState.PENDING_SCAN,
            null,
            null,
            null,
            inserted.issuedAt(),
            inserted.expiresAt(),
            null,
            null,
            inserted.version());

    assertFalse(adapter.transition(forged, forged.markClean(SHA, NOW)).block());
    assertEquals(
        ScanState.PENDING_SCAN,
        adapter.findByTenantAndId(TENANT_A, inserted.uploadId()).block().state());
  }

  @Test
  void anInfectedUploadKeepsItsReasonAndSignature() {
    final AttachmentUpload inserted = adapter.insert(issued(TENANT_A)).block();

    adapter
        .transition(
            inserted,
            inserted.markInfected(SHA, AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, null, NOW))
        .block();

    final AttachmentUpload found = adapter.findByTenantAndId(TENANT_A, inserted.uploadId()).block();
    assertEquals(ScanState.INFECTED, found.state());
    assertEquals(AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, found.rejectionReason());
    assertNull(found.signature());
  }
}
