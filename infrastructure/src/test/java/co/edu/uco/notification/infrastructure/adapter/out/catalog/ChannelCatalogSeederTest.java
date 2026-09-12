package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChannelCatalogSeederTest {

  private final ReactiveMongoTemplate mongoTemplate = Mockito.mock(ReactiveMongoTemplate.class);

  @Test
  void seedsFromPropertiesWhenCollectionIsEmpty() {
    when(mongoTemplate.count(any(), eq(ChannelCatalogDocument.class))).thenReturn(Mono.just(0L));
    when(mongoTemplate.save(any(ChannelCatalogDocument.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    final ChannelCatalogProperties properties =
        new ChannelCatalogProperties(
            Map.of("EMAIL", new ChannelCatalogProperties.ChannelEntry(List.of("brevo"), null)));
    final ChannelCatalogSeeder seeder = new ChannelCatalogSeeder(mongoTemplate, properties);

    StepVerifier.create(seeder.seed()).verifyComplete();

    verify(mongoTemplate).save(new ChannelCatalogDocument("EMAIL", List.of("brevo"), null));
  }

  @Test
  void doesNothingWhenCollectionAlreadyHasData() {
    when(mongoTemplate.count(any(), eq(ChannelCatalogDocument.class))).thenReturn(Mono.just(1L));
    final ChannelCatalogProperties properties = new ChannelCatalogProperties(Map.of());
    final ChannelCatalogSeeder seeder = new ChannelCatalogSeeder(mongoTemplate, properties);

    StepVerifier.create(seeder.seed()).verifyComplete();

    verify(mongoTemplate, never()).save(any(ChannelCatalogDocument.class));
  }

  @Test
  void doesNothingWhenPropertiesHaveNoChannels() {
    when(mongoTemplate.count(any(), eq(ChannelCatalogDocument.class))).thenReturn(Mono.just(0L));
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(mongoTemplate, new ChannelCatalogProperties(null));

    StepVerifier.create(seeder.seed()).verifyComplete();

    verify(mongoTemplate, never()).save(any(ChannelCatalogDocument.class));
  }

  @Test
  void constructorRejectsNullArguments() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () -> new ChannelCatalogSeeder(null, new ChannelCatalogProperties(Map.of())));
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> new ChannelCatalogSeeder(mongoTemplate, null));
  }
}
