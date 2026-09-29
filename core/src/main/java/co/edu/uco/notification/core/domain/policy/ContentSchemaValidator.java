package co.edu.uco.notification.core.domain.policy;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.NotificationContent;
import co.edu.uco.notification.core.exception.InvalidContentException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ContentSchemaValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final String ATTACHMENTS = "attachments";

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
    final JsonSchema jsonSchema = FACTORY.getSchema(schema);
    final Set<ValidationMessage> errors = jsonSchema.validate(toNode(content, attachments));
    if (!errors.isEmpty()) {
      final String details =
          errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
      throw new InvalidContentException(channelType, details);
    }
  }

  private static boolean declaresAttachments(final String schema) {
    if (schema == null || schema.isBlank()) {
      return false;
    }
    try {
      return MAPPER.readTree(schema).path("properties").has(ATTACHMENTS);
    } catch (final JsonProcessingException e) {
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
    }
    return contentNode;
  }

  private static JsonNode toNode(final AttachmentSubmission attachment) {
    final ObjectNode attachmentNode = MAPPER.createObjectNode();
    attachmentNode.put("fileName", attachment.fileName());
    attachmentNode.put("contentType", attachment.contentType());
    attachmentNode.put("sizeBytes", attachment.sizeBytes());
    return attachmentNode;
  }
}
