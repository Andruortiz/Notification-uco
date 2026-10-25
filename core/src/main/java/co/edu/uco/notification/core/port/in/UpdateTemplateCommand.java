package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateId;
import co.edu.uco.notification.core.domain.template.TemplateVariable;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import java.util.List;

public record UpdateTemplateCommand(
    TenantId tenantId,
    TemplateId templateId,
    long version,
    TemplateCategory category,
    String subject,
    String body,
    List<TemplateVariable> variables) {}
