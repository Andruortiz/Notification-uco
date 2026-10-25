package co.edu.uco.notification.core.repository;

import co.edu.uco.notification.core.domain.template.TemplateCategory;
import co.edu.uco.notification.core.domain.template.TemplateStatus;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.TemplateLimitExceededException;
import co.edu.uco.notification.utils.Preconditions;

public record TemplateSearchCriteria(
    TenantId tenantId,
    TemplateCategory category,
    ChannelType channel,
    String language,
    TemplateStatus status,
    int page,
    int size) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 100;

  public TemplateSearchCriteria {
    Preconditions.requireNonNull(tenantId, "tenantId must not be null");
    Preconditions.requireTrue(page >= 0, "page must not be negative");
    if (size < 1 || size > MAX_SIZE) {
      throw new TemplateLimitExceededException("size must be between 1 and " + MAX_SIZE);
    }
  }
}
