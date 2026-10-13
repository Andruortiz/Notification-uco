package co.edu.uco.notification.core.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.uco.notification.core.domain.valueobject.AttachmentRejectionReason;
import co.edu.uco.notification.core.domain.valueobject.ScanVerdict;
import co.edu.uco.notification.core.domain.valueobject.Sha256Digest;
import co.edu.uco.notification.core.domain.valueobject.TenantId;
import co.edu.uco.notification.core.exception.AttachmentInspectionUnavailableException;
import co.edu.uco.notification.core.port.out.ContentTypeDetectorPort;
import co.edu.uco.notification.core.port.out.MalwareScannerPort;
import co.edu.uco.notification.core.port.out.ScanVerdictCachePort;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AttachmentInspectorTest {

  private static final TenantId TENANT = TenantId.of("tenant-1");
  private static final byte[] BYTES = "%PDF-1.4 content".getBytes(StandardCharsets.UTF_8);
  private static final Sha256Digest SHA = Sha256Digest.of(BYTES);

  private ContentTypeDetectorPort detector;
  private MalwareScannerPort scanner;
  private ScanVerdictCachePort cache;
  private AttachmentInspector inspector;

  @BeforeEach
  void setUp() {
    detector = mock(ContentTypeDetectorPort.class);
    scanner = mock(MalwareScannerPort.class);
    cache = mock(ScanVerdictCachePort.class);
    inspector = new AttachmentInspector(detector, scanner, cache);
    when(detector.detect(any(), any())).thenReturn(Mono.just("application/pdf"));
    when(cache.find(any(), any())).thenReturn(Mono.empty());
    when(cache.save(any(), any(), any())).thenReturn(Mono.empty());
    when(scanner.signatureVersion()).thenReturn(Mono.just("100"));
    when(scanner.scan(any())).thenReturn(Mono.just(ScanVerdict.clean("100")));
  }

  private AttachmentInspection inspect(final String declaredType) {
    return inspector.inspect(TENANT, "invoice.pdf", declaredType, BYTES).block();
  }

  @Test
  void scansAnUnknownFileAndCachesTheVerdictByTenantAndHash() {
    final AttachmentInspection inspection = inspect("application/pdf");

    assertTrue(inspection.isClean());
    assertEquals(SHA, inspection.sha256());
    verify(scanner).scan(BYTES);
    verify(cache).save(TENANT, SHA, ScanVerdict.clean("100"));
  }

  @Test
  void reportsMalwareWithTheDetectedSignature() {
    when(scanner.scan(any()))
        .thenReturn(Mono.just(ScanVerdict.infected("Eicar-Test-Signature", "100")));

    final AttachmentInspection inspection = inspect("application/pdf");

    assertFalse(inspection.isClean());
    assertEquals(AttachmentRejectionReason.MALWARE, inspection.rejectionReason());
    assertEquals("Eicar-Test-Signature", inspection.signature());
    verify(cache).save(TENANT, SHA, ScanVerdict.infected("Eicar-Test-Signature", "100"));
  }

  @Test
  void rejectsATypeMismatchWithoutCallingTheScanner() {
    when(detector.detect(any(), any())).thenReturn(Mono.just("application/x-msdownload"));

    final AttachmentInspection inspection = inspect("application/pdf");

    assertEquals(AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, inspection.rejectionReason());
    assertEquals("application/x-msdownload", inspection.detectedContentType());
    assertEquals(SHA, inspection.sha256());
    verify(scanner, never()).scan(any());
    verify(cache, never()).find(any(), any());
  }

  @Test
  void rejectsADetectedTypeOutsideTheWhiteListEvenIfDeclaredTheSame() {
    when(detector.detect(any(), any())).thenReturn(Mono.just("text/html"));

    assertEquals(
        AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, inspect("text/html").rejectionReason());
  }

  @Test
  void acceptsACsvDetectedAsPlainText() {
    when(detector.detect(any(), any())).thenReturn(Mono.just("text/plain"));

    assertTrue(inspect("text/csv").isClean());
  }

  @Test
  void doesNotAcceptPlainTextDeclaredAsCsvTheOtherWayAround() {
    when(detector.detect(any(), any())).thenReturn(Mono.just("text/csv"));

    assertEquals(
        AttachmentRejectionReason.CONTENT_TYPE_MISMATCH, inspect("text/plain").rejectionReason());
  }

  @Test
  void aCachedInfectedVerdictRejectsWithoutScanning() {
    when(cache.find(TENANT, SHA))
        .thenReturn(Mono.just(ScanVerdict.infected("Eicar-Test-Signature", "90")));

    final AttachmentInspection inspection = inspect("application/pdf");

    assertEquals(AttachmentRejectionReason.MALWARE, inspection.rejectionReason());
    verify(scanner, never()).scan(any());
  }

  @Test
  void aCachedCleanVerdictOfTheSameSignatureVersionSkipsTheScanner() {
    when(cache.find(TENANT, SHA)).thenReturn(Mono.just(ScanVerdict.clean("100")));

    assertTrue(inspect("application/pdf").isClean());
    verify(scanner, never()).scan(any());
  }

  @Test
  void aCachedCleanVerdictOfAnotherSignatureVersionIsScannedAgain() {
    when(cache.find(TENANT, SHA)).thenReturn(Mono.just(ScanVerdict.clean("99")));

    assertTrue(inspect("application/pdf").isClean());
    verify(scanner).scan(BYTES);
    verify(cache).save(TENANT, SHA, ScanVerdict.clean("100"));
  }

  @Test
  void scannerFailuresBecomeInspectionUnavailable() {
    when(scanner.scan(any()))
        .thenReturn(Mono.error(new IllegalStateException("connection refused")));

    StepVerifier.create(inspector.inspect(TENANT, "invoice.pdf", "application/pdf", BYTES))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();
  }

  @Test
  void cacheFailuresBecomeInspectionUnavailable() {
    when(cache.find(any(), any())).thenReturn(Mono.error(new IllegalStateException("mongo down")));

    StepVerifier.create(inspector.inspect(TENANT, "invoice.pdf", "application/pdf", BYTES))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();
  }

  @Test
  void detectorFailuresBecomeInspectionUnavailable() {
    when(detector.detect(any(), any())).thenReturn(Mono.error(new IllegalStateException("tika")));

    StepVerifier.create(inspector.inspect(TENANT, "invoice.pdf", "application/pdf", BYTES))
        .expectError(AttachmentInspectionUnavailableException.class)
        .verify();
  }

  @Test
  void aCleanInspectionHasNoRejectionReason() {
    final AttachmentInspection inspection = inspect("application/pdf");

    assertNull(inspection.rejectionReason());
    assertNull(inspection.signature());
  }

  @Test
  void rejectsNullArguments() {
    assertThrows(NullPointerException.class, () -> new AttachmentInspector(null, scanner, cache));
    assertThrows(
        NullPointerException.class,
        () -> inspector.inspect(TENANT, "invoice.pdf", "application/pdf", null));
  }
}
