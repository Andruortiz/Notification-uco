package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateStatus;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.TemplateAlreadyExistsException;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.core.repository.TemplateSearchCriteria;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class TemplateMongoAdapter implements TemplateRepository {

  private final ReactiveMongoTemplate mongoTemplate;

  public TemplateMongoAdapter(final ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
  }

  @Override
  public Mono<Template> insert(final Template template) {
    Preconditions.requireNonNull(template, "template must not be null");
    return mongoTemplate
        .insert(toDocument(template))
        .map(TemplateMongoAdapter::toDomain)
        .onErrorMap(DuplicateKeyException.class, error -> new TemplateAlreadyExistsException());
  }

  @Override
  public Mono<Template> findById(final TenantId tenantId, final TemplateId templateId) {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireNonNull(templateId, "templateId must not be null");
    return mongoTemplate
        .findOne(
            Query.query(
                Criteria.where("_id").is(templateId.value()).and("tenantId").is(tenantId.value())),
            TemplateDocument.class)
        .map(TemplateMongoAdapter::toDomain);
  }

  @Override
  public Mono<Template> replace(final Template next, final long expectedVersion) {
    Preconditions.requireNonNull(next, "next must not be null");
    final Query query =
        Query.query(
            Criteria.where("_id")
                .is(next.id().value())
                .and("tenantId")
                .is(next.tenantId().value())
                .and("version")
                .is(expectedVersion));
    final TemplateDocument document = toDocument(next);
    final Update update =
        new Update()
            .set("category", document.category())
            .set("subject", document.subject())
            .set("body", document.body())
            .set("variables", document.variables())
            .set("status", document.status())
            .set("version", document.version())
            .set("updatedAt", document.updatedAt())
            .set("sampleValues", document.sampleValues());
    return mongoTemplate
        .findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(true), TemplateDocument.class)
        .map(TemplateMongoAdapter::toDomain);
  }

  @Override
  public Flux<Template> search(final TemplateSearchCriteria criteria) {
    Preconditions.requireNonNull(criteria, "criteria must not be null");
    Criteria where = Criteria.where("tenantId").is(criteria.tenantId().value());
    if (criteria.category() != null) {
      where = where.and("category").is(criteria.category().name());
    }
    if (criteria.channel() != null) {
      where = where.and("channel").is(criteria.channel().value().toUpperCase());
    }
    if (criteria.language() != null) {
      where = where.and("language").is(criteria.language());
    }
    if (criteria.status() != null) {
      where = where.and("status").is(criteria.status().name());
    }
    return mongoTemplate
        .find(
            Query.query(where)
                .with(Sort.by("key", "channel", "language"))
                .skip((long) criteria.page() * criteria.size())
                .limit(criteria.size() + 1),
            TemplateDocument.class)
        .map(TemplateMongoAdapter::toDomain);
  }

  private static TemplateDocument toDocument(final Template template) {
    return new TemplateDocument(
        template.id().value(),
        template.tenantId().value(),
        template.key(),
        template.category().name(),
        template.channel().value(),
        template.language(),
        template.subject(),
        template.body(),
        template.variables().stream()
            .map(
                variable ->
                    new TemplateDocument.VariableDocument(variable.name(), variable.required()))
            .toList(),
        template.status().name(),
        template.version(),
        template.createdAt(),
        template.updatedAt(),
        template.sampleValues());
  }

  private static Template toDomain(final TemplateDocument document) {
    return new Template(
        TemplateId.of(document.id()),
        TenantId.of(document.tenantId()),
        document.key(),
        TemplateCategory.valueOf(document.category()),
        ChannelType.of(document.channel()),
        document.language(),
        document.subject(),
        document.body(),
        document.variables().stream()
            .map(variable -> new TemplateVariable(variable.name(), variable.required()))
            .toList(),
        TemplateStatus.valueOf(document.status()),
        document.version(),
        document.createdAt(),
        document.updatedAt(),
        document.sampleValues() == null ? Map.of() : document.sampleValues());
  }
}
