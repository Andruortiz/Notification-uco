package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public record Sha256Digest(String hex) {

  private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public Sha256Digest {
    Preconditions.requireTrue(
        hex != null && HEX_PATTERN.matcher(hex).matches(),
        "Sha256Digest must be 64 lower-case hexadecimal characters");
  }

  public static Sha256Digest of(final byte[] bytes) {
    Preconditions.requireNonNull(bytes, "bytes must not be null");
    try {
      return new Sha256Digest(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public static Sha256Digest fromHex(final String hex) {
    return new Sha256Digest(hex);
  }
}
