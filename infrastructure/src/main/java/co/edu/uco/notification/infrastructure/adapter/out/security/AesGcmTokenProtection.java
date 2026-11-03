package co.edu.uco.notification.infrastructure.adapter.out.security;

import co.edu.uco.notification.utils.Preconditions;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmTokenProtection {

  public static final String PREFIX_ROOT = "enc:v";

  public static final String UNREADABLE = "[unreadable]";
  public static final String UNREADABLE_METER = "encryption.unreadable";

  private static final Pattern ENVELOPE =
      Pattern.compile("^enc:v(\\d{1,9}):([A-Za-z0-9+/]{38,}={0,2})$");
  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec fingerprintKey;
  private final int keyVersion;
  private final int fingerprintKeyVersion;
  private Runnable unreadableIncrement = () -> {};

  public AesGcmTokenProtection(
      final byte[] encryptionKey,
      final int keyVersion,
      final byte[] fingerprintKey,
      final int fingerprintKeyVersion) {
    Preconditions.requireNonNull(encryptionKey, "encryptionKey must not be null");
    Preconditions.requireNonNull(fingerprintKey, "fingerprintKey must not be null");
    Preconditions.requireTrue(
        encryptionKey.length == KEY_BYTES, "the encryption key must have 32 bytes");
    Preconditions.requireTrue(
        fingerprintKey.length >= KEY_BYTES, "the fingerprint key must have at least 32 bytes");
    this.encryptionKey = new SecretKeySpec(encryptionKey, "AES");
    this.fingerprintKey = new SecretKeySpec(fingerprintKey, "HmacSHA256");
    this.keyVersion = keyVersion;
    this.fingerprintKeyVersion = fingerprintKeyVersion;
  }

  public static AesGcmTokenProtection ephemeral() {
    final byte[] encryption = new byte[KEY_BYTES];
    final byte[] fingerprint = new byte[KEY_BYTES];
    RANDOM.nextBytes(encryption);
    RANDOM.nextBytes(fingerprint);
    return new AesGcmTokenProtection(encryption, 1, fingerprint, 1);
  }

  public static byte[] decodeKey(final String encoded, final String name) {
    try {
      return Base64.getDecoder().decode(encoded.trim());
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(name + " must be base64", error);
    }
  }

  public int keyVersion() {
    return keyVersion;
  }

  public int fingerprintKeyVersion() {
    return fingerprintKeyVersion;
  }

  public AesGcmTokenProtection withMeters(final MeterRegistry registry) {
    this.unreadableIncrement =
        registry == null ? () -> {} : registry.counter(UNREADABLE_METER)::increment;
    return this;
  }

  public Optional<String> tryReveal(final String value, final String aad) {
    try {
      return Optional.ofNullable(reveal(value, aad));
    } catch (IllegalStateException error) {
      unreadableIncrement.run();
      return Optional.empty();
    }
  }

  public static Integer versionOf(final String value) {
    final Matcher matcher = value == null ? null : ENVELOPE.matcher(value);
    return matcher != null && matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
  }

  public boolean isProtected(final String value) {
    return value != null && ENVELOPE.matcher(value).matches();
  }

  public String protect(final String plain, final String aad) {
    Preconditions.requireNonNull(plain, "plain must not be null");
    try {
      final byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      final byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      final byte[] joined = Arrays.copyOf(iv, IV_BYTES + encrypted.length);
      System.arraycopy(encrypted, 0, joined, IV_BYTES, encrypted.length);
      return PREFIX_ROOT + keyVersion + ":" + Base64.getEncoder().encodeToString(joined);
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("token encryption failed", error);
    }
  }

  public String reveal(final String value, final String aad) {
    if (!isProtected(value)) {
      return value;
    }
    final String[] parts = value.split(":", 3);
    if (parts.length != 3 || !parts[1].equals("v" + keyVersion)) {
      throw new IllegalStateException("token protected with an unavailable key version");
    }
    try {
      final byte[] joined = Base64.getDecoder().decode(parts[2]);
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          encryptionKey,
          new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(joined, 0, IV_BYTES)));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(joined, IV_BYTES, joined.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException error) {
      throw new IllegalStateException("token decryption failed", error);
    }
  }

  public String fingerprint(final String plain) {
    Preconditions.requireNonNull(plain, "plain must not be null");
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(fingerprintKey);
      return HexFormat.of().formatHex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("token fingerprint failed", error);
    }
  }
}
