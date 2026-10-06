package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.CorrelationId;
import co.edu.uco.notification.utils.ErrorCode;
import co.edu.uco.notification.utils.TraceParent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.logstash.logback.marker.Markers;
import org.slf4j.Marker;

public final class LogFields {

  public static final String CORRELATION_ID = CorrelationId.CONTEXT_KEY;
  public static final String TENANT_ID = "tenantId";
  public static final String NOTIFICATION_ID = "notificationId";
  public static final String TRACE_PARENT = TraceParent.CONTEXT_KEY;
  public static final String FAILURE_CATEGORY = "failureCategory";
  public static final String ERROR_CODE = "errorCode";

  public static final List<String> CONTEXT_KEYS =
      List.of(CORRELATION_ID, TENANT_ID, NOTIFICATION_ID, TRACE_PARENT);

  private LogFields() {}

  public static Marker failure(final ErrorCode code, final Object... keyValues) {
    final Map<String, Object> entries = new LinkedHashMap<>();
    entries.put(ERROR_CODE, code.format());
    entries.put(FAILURE_CATEGORY, code.category());
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      entries.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return Markers.appendEntries(entries);
  }

  public static Marker fields(final Object... keyValues) {
    final Map<String, Object> entries = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      entries.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return Markers.appendEntries(entries);
  }
}
