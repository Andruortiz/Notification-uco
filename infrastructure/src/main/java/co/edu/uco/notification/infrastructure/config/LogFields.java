package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.TraceParent;
import java.util.List;

public final class LogFields {

  public static final String CORRELATION_ID = CorrelationId.CONTEXT_KEY;
  public static final String TENANT_ID = "tenantId";
  public static final String NOTIFICATION_ID = "notificationId";
  public static final String TRACE_PARENT = TraceParent.CONTEXT_KEY;
  public static final String FAILURE_CATEGORY = "failureCategory";

  public static final List<String> CONTEXT_KEYS =
      List.of(CORRELATION_ID, TENANT_ID, NOTIFICATION_ID, TRACE_PARENT);

  private LogFields() {}
}
