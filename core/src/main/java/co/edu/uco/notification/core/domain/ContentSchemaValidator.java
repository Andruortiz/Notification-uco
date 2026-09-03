package co.edu.uco.notification.core.domain;

import co.edu.uco.notification.core.exception.InvalidContentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import java.util.stream.Collectors;

public final class ContentSchemaValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  private ContentSchemaValidator() {}

  public static void validate(
      final ChannelType channelType, final String schema, final NotificationContent content) {
    if (schema == null || schema.isBlank()) {
      return;
    }
    final JsonSchema jsonSchema = FACTORY.getSchema(schema);
    final ObjectNode contentNode = MAPPER.createObjectNode();
    contentNode.put("subject", content.subject());
    contentNode.put("body", content.body());
    final Set<ValidationMessage> errors = jsonSchema.validate(contentNode);
    if (!errors.isEmpty()) {
      final String details =
          errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
      throw new InvalidContentException(channelType, details);
    }
  }
}
