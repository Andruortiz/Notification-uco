package co.edu.uco.notification.infrastructure.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public final class ProtectedResourcesCatalog {

  public static final List<String> ROLES = ApiContract.ROLES;

  private ProtectedResourcesCatalog() {}

  public static List<Map<String, Object>> entries() {
    final List<Map<String, Object>> entries = new ArrayList<>();
    for (final ApiContract.Operation operation : ApiContract.operations()) {
      final Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("method", operation.method());
      entry.put("path", operation.path());
      entry.put("allowedRoles", operation.allowedRoles());
      entry.put("status", operation.status());
      entry.put("operationId", operation.operationId());
      entries.add(entry);
    }
    return entries;
  }

  public static void write(final Path target, final List<Map<String, Object>> entries)
      throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), entries);
  }
}
