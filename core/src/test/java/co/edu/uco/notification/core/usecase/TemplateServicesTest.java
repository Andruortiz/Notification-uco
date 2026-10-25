package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateStatus;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.ChannelNotAvailableException;
import co.edu.uco.notification.core.exception.InvalidTemplateSyntaxException;
import co.edu.uco.notification.core.exception.MissingTemplateVariablesException;
import co.edu.uco.notification.core.exception.TemplateAlreadyExistsException;
import co.edu.uco.notification.core.exception.TemplateArchivedException;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.exception.TemplateNotFoundException;
import co.edu.uco.notification.core.exception.TemplateVersionConflictException;
import co.edu.uco.notification.core.exception.UndeclaredTemplateVariableException;
import co.edu.uco.notification.core.port.in.CreateTemplateCommand;
import co.edu.uco.notification.core.port.in.UpdateTemplateCommand;
import co.edu.uco.notification.core.port.out.ChannelCatalogPort;
import co.edu.uco.notification.core.port.out.ChannelRoute;
import co.edu.uco.notification.core.repository.TemplateRepository;
import co.edu.uco.notification.core.repository.TemplateSearchCriteria;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TemplateServicesTest {

  private static final TenantId TENANT = TenantId.of("tenant-a");
  private static final TenantId OTHER = TenantId.of("tenant-b");
  private static final ChannelType EMAIL = ChannelType.of("email");

  private final Map<String, Template> store = new ConcurrentHashMap<>();
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
  private ChannelCatalogPort catalog;
  private TemplateRepository repository;
  private CreateTemplateService create;
  private UpdateTemplateService update;
  private ArchiveTemplateService archive;
  private QueryTemplatesService query;
  private RenderTemplateService render;

  @BeforeEach
  void setUp() {
    catalog = mock(ChannelCatalogPort.class);
    when(catalog.findActiveRoute(any(), any()))
        .thenReturn(
            Mono.just(
                new ChannelRoute(ChannelType.of("EMAIL"), List.of(ProviderId.of("brevo")), "{}")));
    repository = new InMemoryRepository();
    create = new CreateTemplateService(repository, catalog, clock);
    update = new UpdateTemplateService(repository, clock);
    archive = new ArchiveTemplateService(repository, clock);
    query = new QueryTemplatesService(repository);
    render = new RenderTemplateService(repository);
  }

  @Test
  void createDetectsVariablesAsRequiredByDefaultAndHonoursDeclaredOptionals() {
    final Template created =
        create
            .create(
                command(
                    "welcome",
                    "Hola {{nombre}}, tu curso {{curso}}",
                    "Re: {{curso}}",
                    declared("curso", false)))
            .block();

    assertEquals(
        List.of(new TemplateVariable("curso", false), new TemplateVariable("nombre", true)),
        created.variables());
    assertEquals(TemplateStatus.ACTIVA, created.status());
    assertEquals(0L, created.version());
    assertEquals("EMAIL", created.channel().value());
  }

  @Test
  void createRejectsDeclaredVariablesMissingFromTheText() {
    assertThrows(
        UndeclaredTemplateVariableException.class,
        () -> create.create(command("k", "Hola {{nombre}}", null, declared("otra", true))).block());
  }

  @Test
  void createRejectsInvalidSyntaxAndLimits() {
    assertThrows(
        InvalidTemplateSyntaxException.class,
        () -> create.create(command("k", "Hola {{1abc}}", null, List.of())).block());
    assertThrows(
        TemplateLimitExceededException.class,
        () ->
            create
                .create(command("k", "x".repeat(Template.MAX_BODY + 1), null, List.of()))
                .block());
    assertThrows(
        TemplateLimitExceededException.class,
        () -> create.create(command("k".repeat(61), "b", null, List.of())).block());
    assertThrows(
        TemplateLimitExceededException.class,
        () -> create.create(command("k", "b", "s".repeat(201), List.of())).block());
    final String manyVariables = "{{a}}".repeat(1).concat(" {{b}}".repeat(1));
    final StringBuilder fiftyOne = new StringBuilder();
    for (int i = 0; i < 51; i++) {
      fiftyOne.append("{{v").append(i).append("}} ");
    }
    assertThrows(
        TemplateLimitExceededException.class,
        () -> create.create(command("k", fiftyOne.toString(), null, List.of())).block());
    assertEquals(
        2, create.create(command("ok", manyVariables, null, List.of())).block().variables().size());
  }

  @Test
  void createRejectsAChannelWithoutAnActiveRoute() {
    when(catalog.findActiveRoute(any(), any())).thenReturn(Mono.empty());

    StepVerifier.create(create.create(command("k", "b", null, List.of())))
        .expectError(ChannelNotAvailableException.class)
        .verify();
  }

  @Test
  void createRejectsADuplicateIdentityButAllowsTheSameInAnotherTenant() {
    create.create(command("k", "b", null, List.of())).block();

    StepVerifier.create(create.create(command("k", "b", null, List.of())))
        .expectError(TemplateAlreadyExistsException.class)
        .verify();
    final Template other =
        create
            .create(
                new CreateTemplateCommand(
                    OTHER, "k", TemplateCategory.ACADEMICO, EMAIL, "es", null, "b", List.of()))
            .block();
    assertEquals(OTHER, other.tenantId());
  }

  @Test
  void updateIncrementsTheVersionAndRedetectsVariables() {
    final Template created = create.create(command("k", "Hola {{a}}", null, List.of())).block();

    final Template updated =
        update.update(updateOf(created, 0L, "Hola {{a}} {{b}}", declared("b", false))).block();

    assertEquals(1L, updated.version());
    assertEquals(
        List.of(new TemplateVariable("a", true), new TemplateVariable("b", false)),
        updated.variables());
  }

  @Test
  void updateRejectsStaleVersionsArchivedAndForeignTemplates() {
    final Template created = create.create(command("k", "Hola", null, List.of())).block();

    StepVerifier.create(update.update(updateOf(created, 5L, "x", List.of())))
        .expectError(TemplateVersionConflictException.class)
        .verify();
    StepVerifier.create(
            update.update(
                new UpdateTemplateCommand(
                    OTHER, created.id(), 0L, TemplateCategory.ACADEMICO, null, "x", List.of())))
        .expectError(TemplateNotFoundException.class)
        .verify();
    archive.archive(TENANT, created.id()).block();
    StepVerifier.create(update.update(updateOf(created, 1L, "x", List.of())))
        .expectError(TemplateArchivedException.class)
        .verify();
  }

  @Test
  void archiveMarksTheTemplateAndIsIdempotent() {
    final Template created = create.create(command("k", "Hola", null, List.of())).block();

    final Template archived = archive.archive(TENANT, created.id()).block();
    final Template again = archive.archive(TENANT, created.id()).block();

    assertTrue(archived.archived());
    assertEquals(1L, again.version());
    StepVerifier.create(archive.archive(OTHER, created.id()))
        .expectError(TemplateNotFoundException.class)
        .verify();
  }

  @Test
  void renderListsEveryMissingRequiredVariable() {
    final Template created =
        create
            .create(
                command(
                    "k", "{{nombre}} {{curso}} {{nota}}", "{{nombre}}", declared("nota", false)))
            .block();

    final MissingTemplateVariablesException error =
        assertThrows(
            MissingTemplateVariablesException.class,
            () -> render.render(TENANT, created.id(), Map.of()).block());

    assertEquals(List.of("nombre", "curso"), error.missing());
  }

  @Test
  void renderSubstitutesOptionalsWithEmptyIgnoresExtrasAndKeepsValuesLiteral() {
    final Template created =
        create
            .create(
                command("k", "Hola {{nombre}}{{nota}}", "S {{nombre}}", declared("nota", false)))
            .block();

    final var rendered =
        render.render(TENANT, created.id(), Map.of("nombre", "{{nota}}", "extra", "x")).block();

    assertEquals("Hola {{nota}}", rendered.body());
    assertEquals("S {{nota}}", rendered.subject());
  }

  @Test
  void renderRejectsArchivedAndForeignTemplates() {
    final Template created = create.create(command("k", "Hola", null, List.of())).block();

    assertEquals("Hola", render.render(TENANT, created.id(), null).block().body());
    assertEquals(null, render.render(TENANT, created.id(), null).block().subject());
    StepVerifier.create(render.render(OTHER, created.id(), Map.of()))
        .expectError(TemplateNotFoundException.class)
        .verify();
    archive.archive(TENANT, created.id()).block();
    StepVerifier.create(render.render(TENANT, created.id(), Map.of()))
        .expectError(TemplateArchivedException.class)
        .verify();
  }

  @Test
  void listFiltersPaginatesAndNeverCrossesTenants() {
    for (int i = 0; i < 3; i++) {
      create.create(command("k" + i, "b", null, List.of())).block();
    }
    create
        .create(
            new CreateTemplateCommand(
                TENANT, "en", TemplateCategory.MARKETING, EMAIL, "en", null, "b", List.of()))
        .block();
    create
        .create(
            new CreateTemplateCommand(
                OTHER, "x", TemplateCategory.ACADEMICO, EMAIL, "es", null, "b", List.of()))
        .block();

    final var firstPage = query.list(criteria(TENANT, null, null, 0, 2)).block();
    final var english = query.list(criteria(TENANT, null, "en", 0, 20)).block();

    assertEquals(2, firstPage.items().size());
    assertTrue(firstPage.hasNext());
    assertEquals(1, english.items().size());
    assertFalse(english.hasNext());
    assertTrue(
        query.list(criteria(TENANT, null, null, 0, 20)).block().items().stream()
            .allMatch(t -> t.tenantId().equals(TENANT)));
    assertThrows(TemplateLimitExceededException.class, () -> criteria(TENANT, null, null, 0, 101));
    assertThrows(TemplateLimitExceededException.class, () -> criteria(TENANT, null, null, 0, 0));
    StepVerifier.create(query.get(OTHER, firstPage.items().get(0).id()))
        .expectError(TemplateNotFoundException.class)
        .verify();
  }

  @Test
  void securityCategoryIsCritical() {
    final Template created =
        create
            .create(
                new CreateTemplateCommand(
                    TENANT,
                    "otp",
                    TemplateCategory.SEGURIDAD,
                    EMAIL,
                    "es",
                    null,
                    "{{codigo}}",
                    List.of()))
            .block();

    assertTrue(created.critical());
  }

  @Test
  void detectReturnsVariablesWithoutSavingAndValidatesSyntax() {
    final var detect = new DetectTemplateVariablesService();

    StepVerifier.create(detect.detect("{{a}}", "{{b}} {{a}}"))
        .expectNext(List.of("a", "b"))
        .verifyComplete();
    StepVerifier.create(detect.detect(null, "{{ }}"))
        .expectError(InvalidTemplateSyntaxException.class)
        .verify();
    assertTrue(store.isEmpty());
  }

  private static TemplateSearchCriteria criteria(
      final TenantId tenant,
      final TemplateCategory category,
      final String language,
      final int page,
      final int size) {
    return new TemplateSearchCriteria(tenant, category, null, language, null, page, size);
  }

  private static CreateTemplateCommand command(
      final String key,
      final String body,
      final String subject,
      final List<TemplateVariable> variables) {
    return new CreateTemplateCommand(
        TENANT, key, TemplateCategory.ACADEMICO, EMAIL, "es", subject, body, variables);
  }

  private static UpdateTemplateCommand updateOf(
      final Template template,
      final long version,
      final String body,
      final List<TemplateVariable> variables) {
    return new UpdateTemplateCommand(
        TENANT, template.id(), version, TemplateCategory.ADMINISTRATIVO, null, body, variables);
  }

  private static List<TemplateVariable> declared(final String name, final boolean required) {
    return List.of(new TemplateVariable(name, required));
  }

  private final class InMemoryRepository implements TemplateRepository {

    @Override
    public Mono<Template> insert(final Template template) {
      final boolean duplicate =
          store.values().stream()
              .anyMatch(
                  t ->
                      t.tenantId().equals(template.tenantId())
                          && t.key().equals(template.key())
                          && t.channel().equals(template.channel())
                          && t.language().equals(template.language()));
      if (duplicate) {
        return Mono.error(new TemplateAlreadyExistsException());
      }
      store.put(template.id().value(), template);
      return Mono.just(template);
    }

    @Override
    public Mono<Template> findById(final TenantId tenantId, final TemplateId templateId) {
      return Mono.justOrEmpty(store.get(templateId.value()))
          .filter(t -> t.tenantId().equals(tenantId));
    }

    @Override
    public Mono<Template> replace(final Template next, final long expectedVersion) {
      final Template current = store.get(next.id().value());
      if (current == null || current.version() != expectedVersion) {
        return Mono.empty();
      }
      store.put(next.id().value(), next);
      return Mono.just(next);
    }

    @Override
    public Flux<Template> search(final TemplateSearchCriteria criteria) {
      return Flux.fromStream(
          store.values().stream()
              .filter(t -> t.tenantId().equals(criteria.tenantId()))
              .filter(t -> criteria.language() == null || t.language().equals(criteria.language()))
              .sorted((a, b) -> a.key().compareTo(b.key()))
              .skip((long) criteria.page() * criteria.size())
              .limit(criteria.size() + 1L));
    }
  }
}
