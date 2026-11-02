package co.edu.uco.notification.infrastructure.adapter.in.rest;

import co.edu.uco.notification.core.domain.template.Template;
import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateStatus;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.port.in.RenderedTemplate;
import co.edu.uco.notification.core.port.in.TemplatePage;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TemplateDtos {

  private TemplateDtos() {}

  public record VariableDto(String name, boolean required) {

    TemplateVariable toDomain() {
      return new TemplateVariable(name, required);
    }

    static VariableDto from(final TemplateVariable variable) {
      return new VariableDto(variable.name(), variable.required());
    }
  }

  public record CreateTemplateRequest(
      String key,
      TemplateCategory category,
      String channel,
      String language,
      String subject,
      String body,
      List<VariableDto> variables,
      Map<String, String> sampleValues) {

    public CreateTemplateRequest {
      variables = variables == null ? null : List.copyOf(variables);
      sampleValues = sampleValues == null ? null : Map.copyOf(sampleValues);
    }
  }

  public record UpdateTemplateRequest(
      String templateId,
      Long version,
      TemplateCategory category,
      String subject,
      String body,
      List<VariableDto> variables,
      Map<String, String> sampleValues) {

    public UpdateTemplateRequest {
      variables = variables == null ? null : List.copyOf(variables);
      sampleValues = sampleValues == null ? null : Map.copyOf(sampleValues);
    }
  }

  public record ArchiveTemplateRequest(String templateId) {}

  public record DetectTemplateVariablesRequest(String subject, String body) {}

  public record DetectedTemplateVariablesResponse(List<String> variables) {

    public DetectedTemplateVariablesResponse {
      variables = variables == null ? null : List.copyOf(variables);
    }
  }

  public record RenderTemplateRequest(String templateId, Map<String, String> values) {

    public RenderTemplateRequest {
      values = values == null ? null : Map.copyOf(values);
    }
  }

  public record RenderedTemplateResponse(String subject, String body) {

    static RenderedTemplateResponse from(final RenderedTemplate rendered) {
      return new RenderedTemplateResponse(rendered.subject(), rendered.body());
    }
  }

  public record TemplateResponse(
      String templateId,
      String key,
      TemplateCategory category,
      boolean critical,
      String channel,
      String language,
      String subject,
      String body,
      List<VariableDto> variables,
      TemplateStatus status,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Map<String, String> sampleValues) {

    public TemplateResponse {
      variables = variables == null ? null : List.copyOf(variables);
    }

    static TemplateResponse from(final Template template) {
      return new TemplateResponse(
          template.id().value(),
          template.key(),
          template.category(),
          template.critical(),
          template.channel().value(),
          template.language(),
          template.subject(),
          template.body(),
          template.variables().stream().map(VariableDto::from).toList(),
          template.status(),
          template.version(),
          template.createdAt(),
          template.updatedAt(),
          template.sampleValues());
    }
  }

  public record TemplateListResponse(
      List<TemplateResponse> items, int page, int size, boolean hasNext) {

    public TemplateListResponse {
      items = items == null ? null : List.copyOf(items);
    }

    static TemplateListResponse from(final TemplatePage page) {
      return new TemplateListResponse(
          page.items().stream().map(TemplateResponse::from).toList(),
          page.page(),
          page.size(),
          page.hasNext());
    }
  }
}
