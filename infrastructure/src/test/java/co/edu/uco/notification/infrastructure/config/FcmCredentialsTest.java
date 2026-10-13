package co.edu.uco.notification.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.uco.notification.infrastructure.adapter.out.provider.FcmTestCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FcmCredentialsTest {

  private static final FcmTestCredentials TEST_CREDENTIALS = FcmTestCredentials.shared();

  @TempDir Path tempDir;

  private static FcmProviderProperties properties(final String json, final String file) {
    return new FcmProviderProperties(json, file, null, null, null, null);
  }

  private static String reasonFor(final FcmProviderProperties properties) {
    final FcmCredentials credentials = FcmCredentials.load(properties);
    assertTrue(credentials.serviceAccount().isEmpty());
    return credentials.disabledReason().orElseThrow();
  }

  private static void assertNoCredentialFragments(final String text) {
    assertFalse(text.contains(TEST_CREDENTIALS.privateKeyFragment()), "must not leak the key");
    assertFalse(text.contains(FcmTestCredentials.CLIENT_EMAIL), "must not leak the client email");
    assertFalse(text.contains("test-key-id"), "must not leak the key id");
  }

  @Test
  void loadsAServiceAccountFromTheJsonContent() {
    final FcmCredentials credentials =
        FcmCredentials.load(properties(TEST_CREDENTIALS.json(), null));

    assertTrue(credentials.disabledReason().isEmpty());
    final FcmServiceAccount account = credentials.serviceAccount().orElseThrow();
    assertEquals(FcmTestCredentials.PROJECT_ID, account.projectId());
    assertEquals(FcmTestCredentials.CLIENT_EMAIL, account.clientEmail());
  }

  @Test
  void loadsAServiceAccountFromAMountedFile() throws IOException {
    final Path file = tempDir.resolve("fcm.json");
    Files.writeString(file, TEST_CREDENTIALS.json(), StandardCharsets.UTF_8);

    final FcmCredentials credentials = FcmCredentials.load(properties(null, file.toString()));

    assertTrue(credentials.disabledReason().isEmpty());
    assertEquals(
        FcmTestCredentials.PROJECT_ID, credentials.serviceAccount().orElseThrow().projectId());
  }

  @Test
  void theServiceAccountSignsWithTheKeyOfTheCredential() throws GeneralSecurityException {
    final FcmServiceAccount account =
        FcmCredentials.load(properties(TEST_CREDENTIALS.json(), null))
            .serviceAccount()
            .orElseThrow();
    final byte[] data = "header.claims".getBytes(StandardCharsets.US_ASCII);

    final byte[] signature = account.sign(data);

    final Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(TEST_CREDENTIALS.publicKey());
    verifier.update(data);
    assertTrue(verifier.verify(signature));
  }

  @Test
  void bothSourcesAtOnceAreAmbiguous() throws IOException {
    final Path file = tempDir.resolve("fcm.json");
    Files.writeString(file, TEST_CREDENTIALS.json(), StandardCharsets.UTF_8);

    final String reason = reasonFor(properties(TEST_CREDENTIALS.json(), file.toString()));

    assertTrue(reason.contains("ambiguous"));
    assertTrue(reason.contains("FCM_CREDENTIALS_JSON"));
    assertTrue(reason.contains("FCM_CREDENTIALS_FILE"));
    assertNoCredentialFragments(reason);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void noSourceIsMissing(final String blank) {
    final String reason = reasonFor(properties(blank, blank));

    assertTrue(reason.startsWith("missing"));
    assertTrue(reason.contains("FCM_CREDENTIALS_JSON"));
    assertTrue(reason.contains("FCM_CREDENTIALS_FILE"));
  }

  @Test
  void aFileThatDoesNotExistIsUnreadable() {
    final String missingFile = tempDir.resolve("absent.json").toString();

    final String reason = reasonFor(properties(null, missingFile));

    assertTrue(reason.startsWith("unreadable"));
    assertTrue(reason.contains("FCM_CREDENTIALS_FILE"));
    assertFalse(reason.contains(missingFile));
  }

  @Test
  void aDirectoryIsUnreadable() {
    final String reason = reasonFor(properties(null, tempDir.toString()));

    assertTrue(reason.startsWith("unreadable"));
  }

  static Stream<Arguments> malformedContents() {
    final String json = TEST_CREDENTIALS.json();
    return Stream.of(
        Arguments.of(json.substring(0, json.length() / 2), "not a JSON object"),
        Arguments.of("[" + json + "]", "not a JSON object"),
        Arguments.of("\"" + TEST_CREDENTIALS.privateKeyBase64() + "\"", "not a JSON object"),
        Arguments.of(TEST_CREDENTIALS.jsonWith("type", "authorized_user"), "service_account"),
        Arguments.of(TEST_CREDENTIALS.jsonWithout("type"), "service_account"),
        Arguments.of(TEST_CREDENTIALS.jsonWithout("project_id"), "project_id"),
        Arguments.of(TEST_CREDENTIALS.jsonWith("project_id", " "), "project_id"),
        Arguments.of(TEST_CREDENTIALS.jsonWithout("client_email"), "client_email"),
        Arguments.of(
            TEST_CREDENTIALS.jsonWithout(FcmTestCredentials.PRIVATE_KEY_FIELD),
            FcmTestCredentials.PRIVATE_KEY_FIELD),
        Arguments.of(
            TEST_CREDENTIALS.jsonWith(FcmTestCredentials.PRIVATE_KEY_FIELD, "not-a-key"), "PKCS#8"),
        Arguments.of(
            TEST_CREDENTIALS.jsonWith(FcmTestCredentials.PRIVATE_KEY_FIELD, "QUJDRA=="), "PKCS#8"));
  }

  @ParameterizedTest
  @MethodSource("malformedContents")
  void malformedContentDisablesNamingWhatFailsWithoutLeakingIt(
      final String content, final String expectedDetail) {
    final String reason = reasonFor(properties(content, null));

    assertTrue(reason.startsWith("malformed"), reason);
    assertTrue(reason.contains("FCM_CREDENTIALS_JSON"), reason);
    assertTrue(reason.contains(expectedDetail), reason);
    assertNoCredentialFragments(reason);
  }

  @Test
  void malformedContentFromAFileNamesTheFileVariable() throws IOException {
    final Path file = tempDir.resolve("fcm.json");
    Files.writeString(file, TEST_CREDENTIALS.jsonWithout("client_email"), StandardCharsets.UTF_8);

    final String reason = reasonFor(properties(null, file.toString()));

    assertTrue(reason.contains("FCM_CREDENTIALS_FILE"));
    assertTrue(reason.contains("client_email"));
  }

  @Test
  void noStringRepresentationLeaksTheCredential() {
    final FcmProviderProperties properties = properties(TEST_CREDENTIALS.json(), null);
    final FcmCredentials credentials = FcmCredentials.load(properties);

    assertNoCredentialFragments(properties.toString());
    assertNoCredentialFragments(credentials.toString());
    assertNoCredentialFragments(credentials.serviceAccount().orElseThrow().toString());
  }

  @Test
  void propertiesApplyDefaults() {
    final FcmProviderProperties properties = properties(null, null);

    assertEquals("https://fcm.googleapis.com", properties.baseUrl());
    assertEquals("https://oauth2.googleapis.com/token", properties.tokenUrl());
    assertEquals(10_000L, properties.timeoutMs());
    assertEquals(5_000L, properties.connectTimeoutMs());
  }
}
