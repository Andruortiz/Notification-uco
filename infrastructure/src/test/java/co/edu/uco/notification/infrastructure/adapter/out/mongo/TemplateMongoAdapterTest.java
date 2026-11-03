package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateStatus;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.TemplateAlreadyExistsException;
import co.edu.uco.notification.core.repository.TemplateSearchCriteria;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@DataMongoTest(properties = {"MONGO_USERNAME=test", "MONGO_PASSWORD=test"})
@Testcontainers
class TemplateMongoAdapterTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private static final TenantId TENANT_A = TenantId.of("tenant-a");
  private static final TenantId TENANT_B = TenantId.of("tenant-b");
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  @Autowired private ReactiveMongoTemplate mongoTemplate;

  private TemplateMongoAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new TemplateMongoAdapter(mongoTemplate);
    mongoTemplate.remove(new Query(), TemplateDocument.class).block();
    final var indexOps = mongoTemplate.indexOps(TemplateDocument.class);
    new MongoPersistentEntityIndexResolver(mongoTemplate.getConverter().getMappingContext())
        .resolveIndexFor(TemplateDocument.class)
        .forEach(index -> indexOps.createIndex(index).block());
  }

  private static Template template(
      final TenantId tenant, final String key, final String channel, final String language) {
    return Template.create(
        tenant,
        key,
        TemplateCategory.ACADEMICO,
        ChannelType.of(channel),
        language,
        "Asunto {{nombre}}",
        "Hola {{nombre}} {{curso}}",
        List.of(new TemplateVariable("curso", false)),
        Map.of(),
        NOW);
  }

  @Test
  void insertThenFindByTenantAndIdRoundTrips() {
    final Template inserted = adapter.insert(template(TENANT_A, "k", "EMAIL", "es")).block();

    final Template found = adapter.findById(TENANT_A, inserted.id()).block();

    assertEquals(inserted, found);
    assertEquals(
        List.of(new TemplateVariable("nombre", true), new TemplateVariable("curso", false)),
        found.variables());
  }

  @Test
  void aTemplateOfAnotherTenantIsNeverFound() {
    final Template inserted = adapter.insert(template(TENANT_A, "k", "EMAIL", "es")).block();

    assertNull(adapter.findById(TENANT_B, inserted.id()).block());
  }

  @Test
  void theUniqueIndexRejectsTheSameIdentityInOneTenantOnly() {
    adapter.insert(template(TENANT_A, "k", "EMAIL", "es")).block();

    assertThrows(
        TemplateAlreadyExistsException.class,
        () -> adapter.insert(template(TENANT_A, "k", "EMAIL", "es")).block());
    adapter.insert(template(TENANT_A, "k", "EMAIL", "en")).block();
    adapter.insert(template(TENANT_A, "k", "SMS", "es")).block();
    adapter.insert(template(TENANT_B, "k", "EMAIL", "es")).block();
  }

  @Test
  void replaceAppliesOnlyWhenTheExpectedVersionMatches() {
    final Template inserted = adapter.insert(template(TENANT_A, "k", "EMAIL", "es")).block();
    final Template next = inserted.archive(NOW.plusSeconds(1));

    assertNull(adapter.replace(next, 7L).block());
    final Template replaced = adapter.replace(next, 0L).block();

    assertEquals(TemplateStatus.ARCHIVADA, replaced.status());
    assertEquals(1L, replaced.version());
    assertNull(adapter.replace(next, 0L).block());
  }

  @Test
  void searchFiltersPaginatesAndStaysInsideTheTenant() {
    adapter.insert(template(TENANT_A, "a", "EMAIL", "es")).block();
    adapter.insert(template(TENANT_A, "b", "EMAIL", "en")).block();
    adapter.insert(template(TENANT_A, "c", "SMS", "es")).block();
    adapter.insert(template(TENANT_B, "z", "EMAIL", "es")).block();

    final List<Template> english =
        adapter
            .search(new TemplateSearchCriteria(TENANT_A, null, null, "en", null, 0, 20))
            .collectList()
            .block();
    final List<Template> sms =
        adapter
            .search(
                new TemplateSearchCriteria(
                    TENANT_A, TemplateCategory.ACADEMICO, ChannelType.of("sms"), null, null, 0, 20))
            .collectList()
            .block();
    final List<Template> secondPage =
        adapter
            .search(new TemplateSearchCriteria(TENANT_A, null, null, null, null, 1, 2))
            .collectList()
            .block();
    final List<Template> archived =
        adapter
            .search(
                new TemplateSearchCriteria(
                    TENANT_A, null, null, null, TemplateStatus.ARCHIVADA, 0, 20))
            .collectList()
            .block();

    assertEquals(List.of("b"), english.stream().map(Template::key).toList());
    assertEquals(List.of("c"), sms.stream().map(Template::key).toList());
    assertEquals(1, secondPage.size());
    assertTrue(archived.isEmpty());
    assertFalse(
        adapter
            .search(new TemplateSearchCriteria(TENANT_A, null, null, null, null, 0, 20))
            .collectList()
            .block()
            .stream()
            .anyMatch(t -> t.tenantId().equals(TENANT_B)));
  }

  @Test
  void sampleValuesRoundTrip() {
    final Template inserted = adapter.insert(template(TENANT_A, "samples", "EMAIL", "es")).block();
    final Template replaced =
        adapter
            .replace(
                inserted.update(
                    TemplateCategory.ACADEMICO,
                    null,
                    "Hola {{nombre}} {{curso}}",
                    List.of(new TemplateVariable("curso", false)),
                    Map.of("nombre", "Ana"),
                    NOW),
                inserted.version())
            .block();

    assertEquals(
        Map.of("nombre", "Ana"), adapter.findById(TENANT_A, replaced.id()).block().sampleValues());
  }

  @Test
  void aDocumentWithoutSampleValuesReadsAsAnEmptyMap() {
    final String id = "legacy-" + java.util.UUID.randomUUID();
    mongoTemplate
        .insert(
            new Document("_id", id)
                .append("tenantId", TENANT_A.value())
                .append("key", "legacy")
                .append("category", "ACADEMICO")
                .append("channel", "EMAIL")
                .append("language", "es")
                .append("subject", null)
                .append("body", "Hola")
                .append("variables", List.of())
                .append("status", "ACTIVA")
                .append("version", 0L)
                .append("createdAt", java.util.Date.from(NOW))
                .append("updatedAt", java.util.Date.from(NOW)),
            "templates")
        .block();

    assertTrue(adapter.findById(TENANT_A, TemplateId.of(id)).block().sampleValues().isEmpty());
  }
}
