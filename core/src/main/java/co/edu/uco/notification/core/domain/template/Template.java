package co.edu.uco.notification.core.domain.template;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.core.exception.UndeclaredTemplateVariableException;
import co.edu.uco.notification.utils.Preconditions;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record Template(
    TemplateId id,
    TenantId tenantId,
    String key,
    TemplateCategory category,
    ChannelType channel,
    String language,
    String subject,
    String body,
    List<TemplateVariable> variables,
    TemplateStatus status,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public static final int MAX_KEY = 60;
  public static final int MAX_SUBJECT = 200;
  public static final int MAX_BODY = 20000;
  public static final int MAX_VARIABLES = 50;
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]+");
  private static final Pattern LANGUAGE_PRIMARY = Pattern.compile("[A-Za-z]{2,3}");
  private static final Pattern LANGUAGE_SUBTAG = Pattern.compile("[A-Za-z0-9]{1,8}");

  public Template {
    variables = List.copyOf(variables);
  }

  public static Template create(
      final TenantId tenantId,
      final String key,
      final TemplateCategory category,
      final ChannelType channel,
      final String language,
      final String subject,
      final String body,
      final List<TemplateVariable> declared,
      final Instant now) {
    Preconditions.requireNonBlank(key, "key must not be blank");
    Preconditions.requireNonBlank(language, "language must not be blank");
    final String normalizedLanguage = language.toLowerCase(Locale.ROOT);
    requireLimit(key.length() <= MAX_KEY, "key exceeds " + MAX_KEY + " characters");
    requireLimit(
        language.length() >= 2 && language.length() <= 10, "language must have 2 to 10 characters");
    Preconditions.requireTrue(KEY.matcher(key).matches(), "key has invalid characters");
    Preconditions.requireTrue(isLanguageTag(language), "language is not a valid tag");
    final List<TemplateVariable> variables = resolve(category, subject, body, declared);
    return new Template(
        TemplateId.newId(),
        tenantId,
        key,
        category,
        channel,
        normalizedLanguage,
        subject,
        body,
        variables,
        TemplateStatus.ACTIVA,
        0L,
        now,
        now);
  }

  public Template update(
      final TemplateCategory newCategory,
      final String newSubject,
      final String newBody,
      final List<TemplateVariable> declared,
      final Instant now) {
    return new Template(
        id,
        tenantId,
        key,
        newCategory,
        channel,
        language,
        newSubject,
        newBody,
        resolve(newCategory, newSubject, newBody, declared),
        status,
        version + 1,
        createdAt,
        now);
  }

  public Template archive(final Instant now) {
    return new Template(
        id,
        tenantId,
        key,
        category,
        channel,
        language,
        subject,
        body,
        variables,
        TemplateStatus.ARCHIVADA,
        version + 1,
        createdAt,
        now);
  }

  public boolean archived() {
    return status == TemplateStatus.ARCHIVADA;
  }

  public boolean critical() {
    return category.critical();
  }

  private static List<TemplateVariable> resolve(
      final TemplateCategory category,
      final String subject,
      final String body,
      final List<TemplateVariable> declared) {
    Preconditions.requireNonNull(category, "category must not be null");
    Preconditions.requireNonBlank(body, "body must not be blank");
    requireLimit(
        subject == null || subject.length() <= MAX_SUBJECT,
        "subject exceeds " + MAX_SUBJECT + " characters");
    requireLimit(body.length() <= MAX_BODY, "body exceeds " + MAX_BODY + " characters");
    requireLimit(declared.size() <= MAX_VARIABLES, "more than " + MAX_VARIABLES + " variables");
    final Set<String> detected = TemplateEngine.detect(subject, body);
    requireLimit(detected.size() <= MAX_VARIABLES, "more than " + MAX_VARIABLES + " variables");
    final Map<String, Boolean> required = new HashMap<>();
    for (final TemplateVariable variable : declared) {
      required.put(variable.name(), variable.required());
    }
    final List<String> undeclared =
        required.keySet().stream().filter(name -> !detected.contains(name)).sorted().toList();
    if (!undeclared.isEmpty()) {
      throw new UndeclaredTemplateVariableException(undeclared);
    }
    return detected.stream()
        .map(name -> new TemplateVariable(name, required.getOrDefault(name, Boolean.TRUE)))
        .toList();
  }

  private static boolean isLanguageTag(final String language) {
    final String[] parts = language.split("-", -1);
    return LANGUAGE_PRIMARY.matcher(parts[0]).matches()
        && Arrays.stream(parts, 1, parts.length)
            .allMatch(part -> LANGUAGE_SUBTAG.matcher(part).matches());
  }

  private static void requireLimit(final boolean condition, final String message) {
    if (!condition) {
      throw new TemplateLimitExceededException(message);
    }
  }
}
