package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.port.out.LastKnownConfigurationPort;
import co.edu.uco.notification.utils.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class LastKnownConfigurationMongoAdapter implements LastKnownConfigurationPort {

  private static final Logger LOG =
      LoggerFactory.getLogger(LastKnownConfigurationMongoAdapter.class);

  private final ReactiveMongoTemplate mongoTemplate;
  private final ParameterRegistry registry;
  private final String schemaHash;

  public LastKnownConfigurationMongoAdapter(
      final ReactiveMongoTemplate mongoTemplate, final ParameterRegistry registry) {
    this.mongoTemplate =
        Preconditions.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
    this.registry = Preconditions.requireNonNull(registry, "registry must not be null");
    this.schemaHash = schemaHashOf(registry);
  }

  @Override
  public Mono<ConfigurationSnapshot> load() {
    return mongoTemplate
        .findById(
            LastKnownConfigurationDocument.CURRENT_ID,
            Document.class,
            LastKnownConfigurationDocument.COLLECTION)
        .flatMap(document -> Mono.justOrEmpty(parse(document)));
  }

  @Override
  public Mono<Boolean> saveIfNewer(final ConfigurationSnapshot snapshot) {
    Preconditions.requireNonNull(snapshot, "snapshot must not be null");
    return Mono.defer(() -> upsert(snapshot))
        .onErrorResume(
            DuplicateKeyException.class,
            first ->
                upsert(snapshot)
                    .onErrorResume(DuplicateKeyException.class, second -> Mono.just(false)));
  }

  private Mono<Boolean> upsert(final ConfigurationSnapshot snapshot) {
    final Query query =
        Query.query(
            Criteria.where("_id")
                .is(LastKnownConfigurationDocument.CURRENT_ID)
                .and("version")
                .lt(snapshot.version()));
    final List<Document> values = new ArrayList<>();
    snapshot.values().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                values.add(new Document("key", entry.getKey()).append("value", entry.getValue())));
    final Update update =
        new Update()
            .set("version", snapshot.version())
            .set("values", values)
            .set("schemaHash", schemaHash)
            .set("source", snapshot.source().name())
            .set("adoptedAt", Date.from(snapshot.adoptedAt()));
    return mongoTemplate
        .findAndModify(
            query,
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            Document.class,
            LastKnownConfigurationDocument.COLLECTION)
        .hasElement();
  }

  private java.util.Optional<ConfigurationSnapshot> parse(final Document document) {
    try {
      if (!schemaHash.equals(document.getString("schemaHash"))) {
        LOG.warn("LAST_KNOWN_SCHEMA_MISMATCH document ignored");
        return java.util.Optional.empty();
      }
      final long version = document.get("version", Number.class).longValue();
      final Map<String, Long> values = new HashMap<>();
      for (final Document entry : document.getList("values", Document.class)) {
        final String key = entry.getString("key");
        final Number value = entry.get("value", Number.class);
        if (key == null || value == null || registry.find(key).isEmpty()) {
          LOG.warn("LAST_KNOWN_CORRUPT document ignored");
          return java.util.Optional.empty();
        }
        values.put(key, value.longValue());
      }
      final Date adoptedAt = document.getDate("adoptedAt");
      return java.util.Optional.of(
          new ConfigurationSnapshot(
              version, ConfigurationSource.LAST_KNOWN, values, adoptedAt.toInstant(), Set.of()));
    } catch (final RuntimeException exception) {
      LOG.warn(
          "LAST_KNOWN_CORRUPT document ignored reason={}", exception.getClass().getSimpleName());
      return java.util.Optional.empty();
    }
  }

  private static String schemaHashOf(final ParameterRegistry registry) {
    final StringBuilder description = new StringBuilder();
    registry.descriptors().stream()
        .sorted(Comparator.comparing(ParameterDescriptor::key))
        .forEach(
            descriptor ->
                description
                    .append(descriptor.key())
                    .append('|')
                    .append(descriptor.type())
                    .append('|')
                    .append(descriptor.min())
                    .append('|')
                    .append(descriptor.max())
                    .append('|')
                    .append(descriptor.scope())
                    .append('|')
                    .append(descriptor.scopeIds())
                    .append('|')
                    .append(descriptor.adoption())
                    .append(';'));
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(description.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
