package co.edu.uco.notification.core.usecase;

import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.change;
import static co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures.fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.configuration.AdoptionMode;
import co.edu.uco.notification.core.domain.configuration.ConfigurationFixtures;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.domain.configuration.ConfigurationSource;
import co.edu.uco.notification.core.domain.configuration.ConfigurationValidator;
import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;
import co.edu.uco.notification.core.domain.configuration.ParameterRegistry;
import co.edu.uco.notification.core.port.in.ConfigurationDescription;
import co.edu.uco.notification.core.port.in.ParameterDescription;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class QueryConfigurationServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T11:00:00Z"), ZoneOffset.UTC);
  private static final ParameterDescriptor RESTART_ONLY =
      ParameterDescriptor.global("test.restart-only", 5, 1, 10).withAdoption(AdoptionMode.RESTART);

  private final ParameterRegistry registry = new ParameterRegistry(List.of(RESTART_ONLY));
  private final ConfigurationHolder holder =
      new ConfigurationHolder(ConfigurationFixtures.defaults(registry));
  private final QueryConfigurationService service = new QueryConfigurationService(holder, registry);
  private final ApplyConfigurationChangeService applyService =
      new ApplyConfigurationChangeService(
          holder, new ConfigurationValidator(registry), fixed(), CLOCK);

  @Test
  void describesEveryDescriptorWithItsCurrentValueVersionSourceAndAdoptionInstant() {
    StepVerifier.create(service.describe())
        .assertNext(
            description -> {
              assertEquals(0, description.version());
              assertEquals(ConfigurationSource.DEFAULTS, description.source());
              assertEquals(ConfigurationFixtures.NOW, description.adoptedAt());
              assertEquals(Set.of(), description.pendingRestart());
              assertEquals(registry.descriptors().size(), description.parameters().size());
              for (int index = 0; index < registry.descriptors().size(); index++) {
                final ParameterDescription parameter = description.parameters().get(index);
                assertEquals(registry.descriptors().get(index), parameter.descriptor());
                assertEquals(parameter.descriptor().defaultValue(), parameter.currentValue());
              }
            })
        .verifyComplete();
  }

  @Test
  void reflectsTheNewVersionValuesAndPendingRestartKeysAfterAChangeIsApplied() {
    StepVerifier.create(service.describe())
        .assertNext(description -> assertEquals(0, description.version()))
        .verifyComplete();

    StepVerifier.create(
            applyService.apply(change(1, "dispatch.max-attempts", 7, "test.restart-only", 9)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.describe())
        .assertNext(
            description -> {
              assertEquals(1, description.version());
              assertEquals(ConfigurationSource.PARAMETERS, description.source());
              assertEquals(Instant.parse("2026-10-05T11:00:00Z"), description.adoptedAt());
              assertEquals(Set.of("test.restart-only"), description.pendingRestart());
              assertEquals(7, currentValue(description, "dispatch.max-attempts"));
              assertEquals(5, currentValue(description, "test.restart-only"));
            })
        .verifyComplete();
  }

  @Test
  void fallsBackToTheDefaultValueWhenTheSnapshotHasNoEntryForADescriptor() {
    final ConfigurationHolder sparse =
        new ConfigurationHolder(
            new ConfigurationSnapshot(
                0,
                ConfigurationSource.DEFAULTS,
                java.util.Map.of(),
                ConfigurationFixtures.NOW,
                null));
    final QueryConfigurationService sparseService = new QueryConfigurationService(sparse, registry);

    StepVerifier.create(sparseService.describe())
        .assertNext(
            description ->
                assertTrue(
                    description.parameters().stream()
                        .allMatch(
                            parameter ->
                                parameter.currentValue() == parameter.descriptor().defaultValue())))
        .verifyComplete();
  }

  private static long currentValue(final ConfigurationDescription description, final String key) {
    return description.parameters().stream()
        .filter(parameter -> parameter.descriptor().key().equals(key))
        .findFirst()
        .orElseThrow()
        .currentValue();
  }
}
