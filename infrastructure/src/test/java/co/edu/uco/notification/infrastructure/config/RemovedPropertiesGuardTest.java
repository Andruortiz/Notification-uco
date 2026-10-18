package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RemovedPropertiesGuardTest {

  private static final Set<String> KNOWN_RENAMED =
      Set.of(
          "spring.codec.max-in-memory-size",
          "management.endpoint.prometheus.enabled",
          "management.otlp.tracing.endpoint",
          "spring.data.mongodb.uri");

  private static Set<String> removedByBoot() throws IOException {
    final Set<String> removed = new HashSet<>(KNOWN_RENAMED);
    final Resource[] metadata =
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:META-INF/spring-configuration-metadata.json");
    final JsonMapper mapper = JsonMapper.builder().build();
    for (final Resource resource : metadata) {
      try (InputStream stream = resource.getInputStream()) {
        final JsonNode properties = mapper.readTree(stream).path("properties");
        for (final JsonNode property : properties) {
          final JsonNode deprecation = property.path("deprecation");
          if (property.path("deprecated").asBoolean(false)
              && "error".equals(deprecation.path("level").asString(""))) {
            removed.add(property.path("name").asString());
          }
        }
      }
    }
    return removed;
  }

  private static List<String> configuredKeys(final String yaml) throws IOException {
    final List<String> keys = new ArrayList<>();
    final List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load(yaml, new ClassPathResource(yaml));
    for (final PropertySource<?> source : sources) {
      if (source instanceof EnumerablePropertySource<?> enumerable) {
        keys.addAll(List.of(enumerable.getPropertyNames()));
      }
    }
    return keys;
  }

  @Test
  void metadataOfBootDeclaresRemovedPropertiesSoTheGuardHasSomethingToCheck() throws IOException {
    assertTrue(removedByBoot().size() > KNOWN_RENAMED.size());
  }

  @Test
  void noConfigurationFileSetsAPropertyBootNoLongerReads() throws IOException {
    final Set<String> removed = removedByBoot();
    final List<String> offenders = new ArrayList<>();
    for (final String file : List.of("application.yml", "application-local.yml")) {
      for (final String key : configuredKeys(file)) {
        final String normalized = key.replaceAll("\\[[^\\]]*]", "");
        if (removed.contains(normalized)) {
          offenders.add(file + ": " + key);
        }
      }
    }
    assertEquals(List.of(), offenders);
  }

  @Test
  void theRenamedPropertiesAreTheOnesInUse() throws IOException {
    final List<String> keys = configuredKeys("application.yml");
    assertTrue(keys.contains("spring.mongodb.uri"));
    assertTrue(keys.contains("spring.http.codecs.max-in-memory-size"));
    assertFalse(keys.contains("spring.data.mongodb.uri"));
  }

  @Test
  void theTestPropertiesDoNotUseRemovedKeys() throws IOException {
    final Properties loaded = new Properties();
    try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
      loaded.load(stream);
    }
    final Set<String> removed = removedByBoot();
    for (final String key : loaded.stringPropertyNames()) {
      assertFalse(removed.contains(key), key);
    }
  }
}
