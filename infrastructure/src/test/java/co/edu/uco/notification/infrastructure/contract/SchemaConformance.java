package co.edu.uco.notification.infrastructure.contract;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SchemaConformance {

  private static final String SCHEMA_PREFIX = "#/components/schemas/";

  private SchemaConformance() {}

  public static List<String> violations(final Object value, final String schemaName) {
    final List<String> violations = new ArrayList<>();
    check(value, ApiContract.map(ApiContract.schemas().get(schemaName)), "$", violations);
    return violations;
  }

  private static Map<String, Object> resolve(final Map<String, Object> schema) {
    final Object ref = schema.get("$ref");
    if (ref == null) {
      return schema;
    }
    return resolve(
        ApiContract.map(
            ApiContract.schemas().get(String.valueOf(ref).substring(SCHEMA_PREFIX.length()))));
  }

  private static List<String> typesOf(final Map<String, Object> schema) {
    final Object type = schema.get("type");
    if (type == null) {
      return List.of();
    }
    if (type instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of(String.valueOf(type));
  }

  private static void check(
      final Object value,
      final Map<String, Object> declared,
      final String at,
      final List<String> violations) {
    final Map<String, Object> schema = resolve(declared);
    final List<String> types = typesOf(schema);
    if (value == null) {
      if (!types.isEmpty()
          && !types.contains("null")
          && !Boolean.TRUE.equals(schema.get("nullable"))) {
        violations.add(at + " is null but the schema does not allow null");
      }
      return;
    }
    if (!types.isEmpty() && types.stream().noneMatch(type -> matchesType(value, type))) {
      violations.add(at + " has type " + value.getClass().getSimpleName() + ", expected " + types);
      return;
    }
    if (schema.get("enum") != null && !ApiContract.list(schema.get("enum")).contains(value)) {
      violations.add(at + " value " + value + " is not in " + schema.get("enum"));
    }
    if ("uuid".equals(schema.get("format")) && value instanceof String text && !isUuid(text)) {
      violations.add(at + " is not a uuid: " + text);
    }
    if ("date-time".equals(schema.get("format"))
        && value instanceof String text
        && !isDateTime(text)) {
      violations.add(at + " is not a date-time: " + text);
    }
    if (value instanceof Map<?, ?> object) {
      checkObject(ApiContract.map(object), schema, at, violations);
    }
    if (value instanceof List<?> array && schema.get("items") != null) {
      for (int index = 0; index < array.size(); index++) {
        check(
            array.get(index),
            ApiContract.map(schema.get("items")),
            at + "[" + index + "]",
            violations);
      }
    }
  }

  private static void checkObject(
      final Map<String, Object> object,
      final Map<String, Object> schema,
      final String at,
      final List<String> violations) {
    final Map<String, Object> properties =
        schema.get("properties") == null ? null : ApiContract.map(schema.get("properties"));
    if (schema.get("required") != null) {
      for (final Object required : ApiContract.list(schema.get("required"))) {
        if (!object.containsKey(required)) {
          violations.add(at + "." + required + " is required and missing");
        }
      }
    }
    if (properties == null) {
      return;
    }
    for (final Map.Entry<String, Object> entry : object.entrySet()) {
      if (!properties.containsKey(entry.getKey())) {
        if (schema.get("additionalProperties") == null) {
          violations.add(at + "." + entry.getKey() + " is not declared in the schema");
        }
        continue;
      }
      check(
          entry.getValue(),
          ApiContract.map(properties.get(entry.getKey())),
          at + "." + entry.getKey(),
          violations);
    }
  }

  private static boolean matchesType(final Object value, final String type) {
    return switch (type) {
      case "string" -> value instanceof String;
      case "boolean" -> value instanceof Boolean;
      case "integer" ->
          value instanceof Integer
              || value instanceof Long
              || (value instanceof Number number
                  && number.doubleValue() == Math.rint(number.doubleValue()));
      case "number" -> value instanceof Number;
      case "array" -> value instanceof List<?>;
      case "object" -> value instanceof Map<?, ?>;
      case "null" -> false;
      default -> true;
    };
  }

  private static boolean isUuid(final String text) {
    try {
      UUID.fromString(text);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean isDateTime(final String text) {
    try {
      OffsetDateTime.parse(text);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
