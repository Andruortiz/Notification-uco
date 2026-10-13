package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.Preconditions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.FileSystemResource;

public final class FcmCredentials {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String JSON_SOURCE =
      "notification.provider.fcm.credentials-json (FCM_CREDENTIALS_JSON)";
  private static final String FILE_SOURCE =
      "notification.provider.fcm.credentials-file (FCM_CREDENTIALS_FILE)";
  private static final String SERVICE_ACCOUNT_TYPE = "service_account";
  private static final String PRIVATE_KEY_FIELD = "private_key";
  private static final List<String> REQUIRED_FIELDS =
      List.of("project_id", "client_email", PRIVATE_KEY_FIELD);

  private final FcmServiceAccount serviceAccount;
  private final String disabledReason;

  private FcmCredentials(final FcmServiceAccount serviceAccount, final String disabledReason) {
    this.serviceAccount = serviceAccount;
    this.disabledReason = disabledReason;
  }

  public static FcmCredentials load(final FcmProviderProperties properties) {
    Preconditions.requireNonNull(properties, "properties must not be null");
    final boolean hasJson = !isBlank(properties.credentialsJson());
    final boolean hasFile = !isBlank(properties.credentialsFile());
    if (hasJson && hasFile) {
      return disabled(
          "ambiguous FCM credentials: set only one of " + JSON_SOURCE + " or " + FILE_SOURCE);
    }
    if (!hasJson && !hasFile) {
      return disabled("missing " + JSON_SOURCE + " or " + FILE_SOURCE);
    }
    if (hasJson) {
      return parse(properties.credentialsJson(), JSON_SOURCE);
    }
    final Optional<String> content = read(properties.credentialsFile());
    if (content.isEmpty()) {
      return disabled("unreadable " + FILE_SOURCE);
    }
    return parse(content.get(), FILE_SOURCE);
  }

  public Optional<FcmServiceAccount> serviceAccount() {
    return Optional.ofNullable(serviceAccount);
  }

  public Optional<String> disabledReason() {
    return Optional.ofNullable(disabledReason);
  }

  @Override
  public String toString() {
    return serviceAccount == null
        ? "FcmCredentials[disabled]"
        : "FcmCredentials[projectId=" + serviceAccount.projectId() + "]";
  }

  private static Optional<String> read(final String location) {
    final FileSystemResource resource = new FileSystemResource(location);
    if (!resource.isReadable()) {
      return Optional.empty();
    }
    try {
      return Optional.of(resource.getContentAsString(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private static FcmCredentials parse(final String content, final String source) {
    final JsonNode node;
    try {
      node = MAPPER.readTree(content);
    } catch (final JsonProcessingException e) {
      return malformed(source, "content is not a JSON object");
    }
    if (node == null || !node.isObject()) {
      return malformed(source, "content is not a JSON object");
    }
    if (!SERVICE_ACCOUNT_TYPE.equals(node.path("type").asText(null))) {
      return malformed(source, "expected type " + SERVICE_ACCOUNT_TYPE);
    }
    for (final String field : REQUIRED_FIELDS) {
      if (isBlank(node.path(field).asText(null))) {
        return malformed(source, "missing field " + field);
      }
    }
    try {
      return new FcmCredentials(
          FcmServiceAccount.fromPem(
              node.path("project_id").asText(),
              node.path("client_email").asText(),
              node.path(PRIVATE_KEY_FIELD).asText()),
          null);
    } catch (final IllegalArgumentException e) {
      return malformed(source, PRIVATE_KEY_FIELD + " is not a readable PKCS#8 RSA key");
    }
  }

  private static FcmCredentials malformed(final String source, final String detail) {
    return disabled("malformed FCM credentials in " + source + ": " + detail);
  }

  private static FcmCredentials disabled(final String reason) {
    return new FcmCredentials(null, reason);
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
