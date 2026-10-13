package co.edu.uco.notification.infrastructure.adapter.out.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import co.edu.uco.notification.infrastructure.support.SampleFiles;
import org.junit.jupiter.api.Test;

class TikaContentTypeDetectorAdapterTest {

  private final TikaContentTypeDetectorAdapter adapter = new TikaContentTypeDetectorAdapter();

  private String detect(final byte[] content, final String fileName) {
    return adapter.detect(content, fileName).block();
  }

  @Test
  void detectsEveryTypeOfTheWhiteListFromRealContent() {
    assertEquals("application/pdf", detect(SampleFiles.pdf("x"), "invoice.pdf"));
    assertEquals("image/png", detect(SampleFiles.png(), "photo.png"));
    assertEquals("image/jpeg", detect(SampleFiles.jpeg(), "photo.jpg"));
    assertEquals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        detect(SampleFiles.docx(), "letter.docx"));
    assertEquals(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        detect(SampleFiles.xlsx(), "sheet.xlsx"));
    assertEquals("text/csv", detect(SampleFiles.text("a,b\n1,2\n"), "data.csv"));
    assertEquals("text/plain", detect(SampleFiles.text("hello world\n"), "notes.txt"));
  }

  @Test
  void theContentWinsOverTheNameForImagesAndDocuments() {
    assertEquals("image/png", detect(SampleFiles.png(), "invoice.pdf"));
    assertEquals("application/pdf", detect(SampleFiles.pdf("x"), "photo.png"));
  }

  @Test
  void anExecutableRenamedToPdfIsNotPdf() {
    assertNotEquals("application/pdf", detect(SampleFiles.executable(), "invoice.pdf"));
  }

  @Test
  void htmlDeclaredAsTextIsDetectedAsHtml() {
    assertEquals(
        "text/html",
        detect(SampleFiles.text("<html><body><script>alert(1)</script></body></html>"), "a.txt"));
  }

  @Test
  void aZipWithoutOfficeStructureIsNotADocx() {
    assertNotEquals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        detect(zipWithoutContentTypes(), "letter.docx"));
  }

  @Test
  void aSpreadsheetNamedAsADocumentIsDetectedAsASpreadsheet() {
    assertEquals(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        detect(SampleFiles.xlsx(), "letter.docx"));
  }

  @Test
  void aTruncatedZipNamedAsADocumentIsJustAZip() {
    final byte[] docx = SampleFiles.docx();
    final byte[] truncated = java.util.Arrays.copyOf(docx, 40);

    assertEquals("application/zip", detect(truncated, "letter.docx"));
  }

  private static byte[] zipWithoutContentTypes() {
    try {
      final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
        zip.putNextEntry(new java.util.zip.ZipEntry("payload.exe"));
        zip.write(SampleFiles.executable());
        zip.closeEntry();
      }
      return out.toByteArray();
    } catch (final java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
