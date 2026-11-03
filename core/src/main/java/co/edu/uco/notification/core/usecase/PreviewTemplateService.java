package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.template.SmsSegmenter;
import co.edu.uco.notification.core.domain.template.TemplatePreview;
import co.edu.uco.notification.core.port.in.PreviewCommand;
import co.edu.uco.notification.core.port.in.PreviewTemplateUseCase;
import co.edu.uco.notification.utils.Preconditions;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Mono;

public final class PreviewTemplateService implements PreviewTemplateUseCase {

  private static final String SMS = "SMS";

  private final RenderTemplateService renderService;

  public PreviewTemplateService(final RenderTemplateService renderService) {
    this.renderService =
        Preconditions.requireNonNull(renderService, "renderService must not be null");
  }

  @Override
  public Mono<TemplatePreview> preview(final PreviewCommand command) {
    Preconditions.requireNonNull(command, "command must not be null");
    return Mono.fromSupplier(() -> build(command));
  }

  private TemplatePreview build(final PreviewCommand command) {
    final Map<String, String> values = command.values() == null ? Map.of() : command.values();
    final ResolvedTemplate resolved =
        renderService.resolveContent(
            command.channel(), command.subject(), command.body(), command.variables(), values);
    final boolean sms = SMS.equals(command.channel().value().toUpperCase(Locale.ROOT));
    return new TemplatePreview(
        resolved.subject(),
        resolved.body(),
        RenderTemplateService.missing(resolved, values),
        codePoints(resolved.body()),
        codePoints(resolved.subject()),
        sms ? SmsSegmenter.segment(resolved.body()) : null);
  }

  private static int codePoints(final String text) {
    return text == null ? 0 : text.codePointCount(0, text.length());
  }
}
