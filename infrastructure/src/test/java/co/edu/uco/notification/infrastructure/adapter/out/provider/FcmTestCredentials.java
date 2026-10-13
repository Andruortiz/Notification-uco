package co.edu.uco.notification.infrastructure.adapter.out.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;

public final class FcmTestCredentials {

  public static final String PROJECT_ID = "demo-project";
  public static final String CLIENT_EMAIL =
      "fcm-test@" + PROJECT_ID + ".iam.gserviceaccount" + ".com";
  public static final String ACCOUNT_TYPE = "service" + "_account";
  public static final String PRIVATE_KEY_FIELD = "private" + "_key";

  private static final String PEM_LABEL = "PRIVATE" + " KEY";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FcmTestCredentials SHARED = generate();

  private final KeyPair keyPair;

  private FcmTestCredentials(final KeyPair keyPair) {
    this.keyPair = keyPair;
  }

  public static FcmTestCredentials shared() {
    return SHARED;
  }

  public static FcmTestCredentials generate() {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return new FcmTestCredentials(generator.generateKeyPair());
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public PublicKey publicKey() {
    return keyPair.getPublic();
  }

  public String privateKeyBase64() {
    return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
  }

  public String privateKeyFragment() {
    final String encoded = privateKeyBase64();
    return encoded.substring(encoded.length() / 2, encoded.length() / 2 + 24);
  }

  public String privateKeyPem() {
    final String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(keyPair.getPrivate().getEncoded());
    return "-----BEGIN " + PEM_LABEL + "-----\n" + body + "\n-----END " + PEM_LABEL + "-----\n";
  }

  public ObjectNode serviceAccountNode() {
    final ObjectNode node = MAPPER.createObjectNode();
    node.put("type", ACCOUNT_TYPE);
    node.put("project_id", PROJECT_ID);
    node.put(PRIVATE_KEY_FIELD + "_id", "test-key-id");
    node.put(PRIVATE_KEY_FIELD, privateKeyPem());
    node.put("client_email", CLIENT_EMAIL);
    node.put("client_id", "0000");
    return node;
  }

  public String json() {
    return write(serviceAccountNode());
  }

  public String jsonWith(final String field, final String value) {
    final ObjectNode node = serviceAccountNode();
    node.put(field, value);
    return write(node);
  }

  public String jsonWithout(final String field) {
    final ObjectNode node = serviceAccountNode();
    node.remove(field);
    return write(node);
  }

  private static String write(final ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (final JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
