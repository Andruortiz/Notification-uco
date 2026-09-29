package co.edu.uco.notification.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.UploadId;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class Sha256DigestTest {

  @Test
  void computesTheKnownDigestOfAFixedContent() {
    assertEquals(
        "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        Sha256Digest.of("test".getBytes(StandardCharsets.UTF_8)).hex());
  }

  @Test
  void acceptsAValidHexValue() {
    final String hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    assertEquals(hex, Sha256Digest.fromHex(hex).hex());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08",
        "9f86d081",
        "zz86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
      })
  void rejectsAMalformedHexValue(final String hex) {
    assertThrows(IllegalArgumentException.class, () -> Sha256Digest.fromHex(hex));
  }

  @Test
  void uploadIdRejectsBlankValuesAndGeneratesDistinctIds() {
    assertThrows(IllegalArgumentException.class, () -> UploadId.of(" "));
    assertThrows(IllegalArgumentException.class, () -> UploadId.of(null));
    final UploadId first = UploadId.newId();
    assertNotEquals(first, UploadId.newId());
    assertTrue(first.value().matches("[0-9a-f-]{36}"), first.value());
  }
}
