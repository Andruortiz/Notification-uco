package co.edu.uco.notification.infrastructure.config;

import co.edu.uco.notification.utils.Preconditions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Pattern;

public final class FcmServiceAccount {

  private static final Pattern PEM_BOUNDARY = Pattern.compile("-----[A-Z0-9 ]+-----");
  private static final Pattern WHITESPACE = Pattern.compile("\\s");

  private final String projectId;
  private final String clientEmail;
  private final PrivateKey privateKey;

  private FcmServiceAccount(
      final String projectId, final String clientEmail, final PrivateKey privateKey) {
    this.projectId = projectId;
    this.clientEmail = clientEmail;
    this.privateKey = privateKey;
  }

  static FcmServiceAccount fromPem(
      final String projectId, final String clientEmail, final String privateKeyPem) {
    Preconditions.requireNonBlank(projectId, "projectId must not be blank");
    Preconditions.requireNonBlank(clientEmail, "clientEmail must not be blank");
    Preconditions.requireNonBlank(privateKeyPem, "privateKeyPem must not be blank");
    final String base64 =
        WHITESPACE.matcher(PEM_BOUNDARY.matcher(privateKeyPem).replaceAll("")).replaceAll("");
    try {
      final byte[] der = Base64.getDecoder().decode(base64);
      final PrivateKey key =
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      return new FcmServiceAccount(projectId, clientEmail, key);
    } catch (final IllegalArgumentException | GeneralSecurityException e) {
      throw new IllegalArgumentException("private key is not a readable PKCS#8 RSA key");
    }
  }

  public String projectId() {
    return projectId;
  }

  public String clientEmail() {
    return clientEmail;
  }

  public byte[] sign(final byte[] data) {
    Preconditions.requireNonNull(data, "data must not be null");
    try {
      final Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(privateKey);
      signature.update(data);
      return signature.sign();
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("could not sign with the FCM service account key");
    }
  }

  @Override
  public String toString() {
    return "FcmServiceAccount[projectId=" + projectId + "]";
  }
}
