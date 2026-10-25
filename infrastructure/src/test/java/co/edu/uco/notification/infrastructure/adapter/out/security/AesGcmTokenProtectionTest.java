package co.edu.uco.notification.infrastructure.adapter.out.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AesGcmTokenProtectionTest {

  private static byte[] key(final int fill) {
    final byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) fill);
    return bytes;
  }

  private final AesGcmTokenProtection protection = new AesGcmTokenProtection(key(1), 1, key(2), 1);

  @Test
  void protectsAndRevealsWithTheSameAad() {
    final String protectedToken = protection.protect("fcm-token-123", "tenant-1|dev-1");

    assertTrue(protectedToken.startsWith("enc:v1:"));
    assertFalse(protectedToken.contains("fcm-token-123"));
    assertEquals("fcm-token-123", protection.reveal(protectedToken, "tenant-1|dev-1"));
  }

  @Test
  void encryptionIsRandomizedPerCall() {
    assertNotEquals(protection.protect("same", "aad"), protection.protect("same", "aad"));
  }

  @Test
  void aDifferentAadOrKeyFailsWithoutLeakingTheToken() {
    final String protectedToken = protection.protect("fcm-token-123", "tenant-1|dev-1");
    final AesGcmTokenProtection otherKey = new AesGcmTokenProtection(key(9), 1, key(2), 1);

    final IllegalStateException wrongAad =
        assertThrows(
            IllegalStateException.class, () -> protection.reveal(protectedToken, "tenant-2|dev-1"));
    assertThrows(
        IllegalStateException.class, () -> otherKey.reveal(protectedToken, "tenant-1|dev-1"));
    assertFalse(String.valueOf(wrongAad.getMessage()).contains("fcm-token-123"));
  }

  @Test
  void aTokenProtectedWithAnotherKeyVersionIsRejected() {
    final String protectedToken = protection.protect("fcm-token-123", "aad");
    final AesGcmTokenProtection rotated = new AesGcmTokenProtection(key(1), 2, key(2), 1);

    assertThrows(IllegalStateException.class, () -> rotated.reveal(protectedToken, "aad"));
  }

  @Test
  void legacyPlainValuesAreReturnedUnchanged() {
    assertEquals("plain-token", protection.reveal("plain-token", "aad"));
    assertFalse(protection.isProtected("plain-token"));
  }

  @Test
  void fingerprintIsDeterministicAndKeyDependent() {
    final AesGcmTokenProtection otherFingerprintKey =
        new AesGcmTokenProtection(key(1), 1, key(3), 1);

    assertEquals(protection.fingerprint("t"), protection.fingerprint("t"));
    assertNotEquals(protection.fingerprint("t"), protection.fingerprint("u"));
    assertNotEquals(protection.fingerprint("t"), otherFingerprintKey.fingerprint("t"));
    assertEquals(64, protection.fingerprint("t").length());
  }

  @Test
  void rejectsKeysWithTheWrongSize() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AesGcmTokenProtection(new byte[16], 1, key(2), 1));
    assertThrows(
        IllegalArgumentException.class, () -> new AesGcmTokenProtection(key(1), 1, new byte[8], 1));
    assertThrows(IllegalStateException.class, () -> AesGcmTokenProtection.decodeKey("!!", "K"));
  }

  @Test
  void ephemeralKeysWork() {
    final AesGcmTokenProtection ephemeral = AesGcmTokenProtection.ephemeral();

    assertEquals("x", ephemeral.reveal(ephemeral.protect("x", "a"), "a"));
  }
}
