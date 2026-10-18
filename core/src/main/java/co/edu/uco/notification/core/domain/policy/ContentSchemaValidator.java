package co.edu.uco.notification.core.domain.policy;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.exception.InvalidContentException;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class ContentSchemaValidator {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder.schemaRegistryConfig(
                  SchemaRegistryConfig.builder().pathType(PathType.LEGACY).build()));
  private static final String ATTACHMENTS = "attachments";

  private static final String ATTACHMENTS_TOTAL_BYTES = "attachmentsTotalBytes";

  private ContentSchemaValidator() {}

  public static void validate(
      final ChannelType channelType,
      final String schema,
      final NotificationContent content,
      final List<AttachmentSubmission> attachments) {
    if (!attachments.isEmpty() && !declaresAttachments(schema)) {
      throw new InvalidContentException(channelType, "channel does not accept attachments");
    }
    if (schema == null || schema.isBlank()) {
      return;
    }
    final Schema jsonSchema = REGISTRY.getSchema(schema);
    final List<Error> errors = jsonSchema.validate(toNode(content, attachments));
    if (!errors.isEmpty()) {
      final String details =
          errors.stream()
              .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
              .collect(Collectors.joining("; "));
      throw new InvalidContentException(channelType, details);
    }
  }

  private static boolean declaresAttachments(final String schema) {
    if (schema == null || schema.isBlank()) {
      return false;
    }
    try {
      return MAPPER.readTree(schema).path("properties").has(ATTACHMENTS);
    } catch (final JacksonException e) {
      return false;
    }
  }

  private static JsonNode toNode(
      final NotificationContent content, final List<AttachmentSubmission> attachments) {
    final ObjectNode contentNode = MAPPER.createObjectNode();
    contentNode.put("subject", content.subject());
    contentNode.put("body", content.body());
    if (!attachments.isEmpty()) {
      final ArrayNode attachmentsNode = contentNode.putArray(ATTACHMENTS);
      attachments.forEach(attachment -> attachmentsNode.add(toNode(attachment)));
      contentNode.put(ATTACHMENTS_TOTAL_BYTES, totalSize(attachments));
    }
    return contentNode;
  }

  private static long totalSize(final List<AttachmentSubmission> attachments) {
    return attachments.stream()
        .map(AttachmentSubmission::sizeBytes)
        .filter(java.util.Objects::nonNull)
        .mapToLong(Long::longValue)
        .sum();
  }

  private static JsonNode toNode(final AttachmentSubmission attachment) {
    final ObjectNode attachmentNode = MAPPER.createObjectNode();
    attachmentNode.put("fileName", attachment.fileName());
    attachmentNode.put("contentType", attachment.contentType());
    attachmentNode.put("sizeBytes", attachment.sizeBytes());
    return attachmentNode;
  }
}
