package co.edu.uco.notification.infrastructure.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.infrastructure.config.ChannelCatalogProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChannelCatalogSeederTest {

  private final ReactiveMongoTemplate mongoTemplate = Mockito.mock(ReactiveMongoTemplate.class);

  private static ChannelCatalogProperties.ChannelEntry entry(final String... providers) {
    return new ChannelCatalogProperties.ChannelEntry(List.of(providers), null);
  }

  private void channelIsMissing(final String channelType) {
    when(mongoTemplate.findById(eq(channelType), eq(ChannelCatalogDocument.class)))
        .thenReturn(Mono.empty());
  }

  private void channelIsStored(final ChannelCatalogDocument stored) {
    when(mongoTemplate.findById(eq(stored.channelType()), eq(ChannelCatalogDocument.class)))
        .thenReturn(Mono.just(stored));
  }

  private void insertSucceeds() {
    when(mongoTemplate.insert(any(ChannelCatalogDocument.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
  }

  @Test
  void seedsAChannelThatIsMissing() {
    channelIsMissing("EMAIL");
    insertSucceeds();
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(
            mongoTemplate, new ChannelCatalogProperties(Map.of("EMAIL", entry("brevo"))));

    StepVerifier.create(seeder.seed()).verifyComplete();

    verify(mongoTemplate).insert(new ChannelCatalogDocument("EMAIL", List.of("brevo"), null));
  }

  @Test
  void neverModifiesAChannelThatIsAlreadyStored() {
    channelIsStored(new ChannelCatalogDocument("EMAIL", List.of("simulated"), "{}"));
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(
            mongoTemplate, new ChannelCatalogProperties(Map.of("EMAIL", entry("brevo"))));

    StepVerifier.create(seeder.seed()).verifyComplete();

    verify(mongoTemplate, never()).insert(any(ChannelCatalogDocument.class));
    verify(mongoTemplate, never()).save(any(ChannelCatalogDocument.class));
  }

  @Test
  void leavesAnIdenticalStoredChannelUntouched() {
    channelIsStored(new ChannelCatalogDocument("SMS", List.of("twilio"), null));
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(
            mongoTemplate, new ChannelCatalogProperties(Map.of("SMS", entry("twilio"))));

    StepVerifier.create(seeder.seed()).verifyComplete();

    verify(mongoTemplate, never()).insert(any(ChannelCatalogDocument.class));
  }

  @Test
  void seedsOnlyTheChannelsThatAreMissing() {
    channelIsStored(new ChannelCatalogDocument("EMAIL", List.of("simulated"), null));
    channelIsMissing("SMS");
    channelIsMissing("PUSH");
    insertSucceeds();
    final Map<String, ChannelCatalogProperties.ChannelEntry> channels = new LinkedHashMap<>();
    channels.put("EMAIL", entry("brevo"));
    channels.put("SMS", entry("twilio"));
    channels.put("PUSH", entry("fcm"));
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(mongoTemplate, new ChannelCatalogProperties(channels));

    StepVerifier.create(seeder.seed()).verifyComplete();

    verify(mongoTemplate).insert(new ChannelCatalogDocument("SMS", List.of("twilio"), null));
    verify(mongoTemplate).insert(new ChannelCatalogDocument("PUSH", List.of("fcm"), null));
    verify(mongoTemplate, never())
        .insert(new ChannelCatalogDocument("EMAIL", List.of("brevo"), null));
  }

  @Test
  void ignoresADuplicateKeyFromAConcurrentSeed() {
    channelIsMissing("EMAIL");
    when(mongoTemplate.insert(any(ChannelCatalogDocument.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("already inserted")));
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(
            mongoTemplate, new ChannelCatalogProperties(Map.of("EMAIL", entry("brevo"))));

    StepVerifier.create(seeder.seed()).verifyComplete();
  }

  @Test
  void seedPropagatesAnyOtherError() {
    channelIsMissing("EMAIL");
    when(mongoTemplate.insert(any(ChannelCatalogDocument.class)))
        .thenReturn(Mono.error(new IllegalStateException("mongo is down")));
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(
            mongoTemplate, new ChannelCatalogProperties(Map.of("EMAIL", entry("brevo"))));

    StepVerifier.create(seeder.seed()).expectError(IllegalStateException.class).verify();
  }

  @Test
  void startupDoesNotFailWhenSeedingFails() {
    when(mongoTemplate.findById(eq("EMAIL"), eq(ChannelCatalogDocument.class)))
        .thenReturn(Mono.error(new IllegalStateException("mongo is down")));
    final ChannelCatalogSeeder seeder =
        new ChannelCatalogSeeder(
            mongoTemplate, new ChannelCatalogProperties(Map.of("EMAIL", entry("brevo"))));

    assertDoesNotThrow(() -> seeder.run(null));
  }

  @Test
  void doesNothingWhenThereAreNoChannelsConfigured() {
    final ChannelCatalogSeeder withNull =
        new ChannelCatalogSeeder(mongoTemplate, new ChannelCatalogProperties(null));
    final ChannelCatalogSeeder withEmpty =
        new ChannelCatalogSeeder(mongoTemplate, new ChannelCatalogProperties(Map.of()));

    StepVerifier.create(withNull.seed()).verifyComplete();
    StepVerifier.create(withEmpty.seed()).verifyComplete();

    verify(mongoTemplate, never()).insert(any(ChannelCatalogDocument.class));
  }

  @Test
  void constructorRejectsNullArguments() {
    assertThrows(
        NullPointerException.class,
        () -> new ChannelCatalogSeeder(null, new ChannelCatalogProperties(Map.of())));
    assertThrows(NullPointerException.class, () -> new ChannelCatalogSeeder(mongoTemplate, null));
  }
}
