package co.edu.uco.notification.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.utils.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ErrorResponseContractTest {

  private static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponse";
  private static final String RESPONSE_REF_PREFIX = "#/components/responses/";

  private static Map<String, Object> document;

  @BeforeAll
  static void load() throws IOException {
    try (InputStream input =
        ErrorResponseContractTest.class
            .getClassLoader()
            .getResourceAsStream("static/openapi/api-notificaciones.yaml")) {
      assertNotNull(input);
      document = new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(final Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(final Object value) {
    return (List<Object>) value;
  }

  private static Map<String, Object> components(final String section) {
    return map(map(document.get("components")).get(section));
  }

  @Test
  void errorResponseRequiresCodeAndCorrelationId() {
    final Map<String, Object> schema = map(components("schemas").get("ErrorResponse"));

    assertTrue(list(schema.get("required")).contains("code"));
    assertTrue(list(schema.get("required")).contains("correlationId"));
  }

  @Test
  void codeEnumerationEqualsTheCatalog() {
    final Map<String, Object> schema = map(components("schemas").get("ErrorResponse"));
    final Map<String, Object> code = map(map(schema.get("properties")).get("code"));

    final Set<Object> documented = new LinkedHashSet<>(list(code.get("enum")));
    final Set<Object> catalog =
        Arrays.stream(ErrorCode.values())
            .map(ErrorCode::format)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertEquals(catalog, documented);
    assertEquals(ErrorCode.values().length, list(code.get("enum")).size());
  }

  @Test
  void everyErrorResponseOfEveryOperationReferencesTheErrorSchema() {
    final Map<String, Object> paths = map(document.get("paths"));
    int checked = 0;
    for (final Map.Entry<String, Object> path : paths.entrySet()) {
      for (final Map.Entry<String, Object> operation : map(path.getValue()).entrySet()) {
        if (!(operation.getValue() instanceof Map<?, ?>)
            || !map(operation.getValue()).containsKey("responses")) {
          continue;
        }
        final Map<String, Object> responses = map(map(operation.getValue()).get("responses"));
        for (final Map.Entry<String, Object> response : responses.entrySet()) {
          if (Integer.parseInt(response.getKey()) < 400) {
            continue;
          }
          assertTrue(
              referencesErrorSchema(map(response.getValue())),
              path.getKey() + " " + operation.getKey() + " " + response.getKey());
          checked++;
        }
      }
    }
    assertTrue(checked > 20);
  }

  @Test
  void everyReusableErrorResponseReferencesTheErrorSchema() {
    for (final Map.Entry<String, Object> response : components("responses").entrySet()) {
      assertTrue(referencesErrorSchema(map(response.getValue())), response.getKey());
    }
  }

  private static boolean referencesErrorSchema(final Map<String, Object> response) {
    final Object ref = response.get("$ref");
    if (ref != null) {
      final String name = String.valueOf(ref).substring(RESPONSE_REF_PREFIX.length());
      return referencesErrorSchema(map(components("responses").get(name)));
    }
    final Map<String, Object> content = map(response.get("content"));
    if (content == null) {
      return false;
    }
    final Map<String, Object> json = map(content.get("application/json"));
    return json != null && ERROR_SCHEMA_REF.equals(map(json.get("schema")).get("$ref"));
  }
}
