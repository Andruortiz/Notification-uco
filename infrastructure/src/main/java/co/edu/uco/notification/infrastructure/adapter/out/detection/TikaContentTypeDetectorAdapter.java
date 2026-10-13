package co.edu.uco.notification.infrastructure.adapter.out.detection;

import co.edu.uco.notification.core.domain.valueobject.AttachmentSubmission;
import co.edu.uco.notification.core.port.out.ContentTypeDetectorPort;
import co.edu.uco.notification.utils.Preconditions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class TikaContentTypeDetectorAdapter implements ContentTypeDetectorPort {

  static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final String ZIP = "application/zip";
  private static final String CONTENT_TYPES_ENTRY = "[Content_Types].xml";
  private static final int MAX_CONTENT_TYPES_BYTES = 1_048_576;
  private static final Set<String> ZIP_FAMILY =
      Set.of(
          ZIP,
          "application/x-tika-ooxml",
          DOCX,
          XLSX,
          "application/vnd.openxmlformats-officedocument.presentationml.presentation");

  private final Tika tika = new Tika();

  @Override
  public Mono<String> detect(final byte[] content, final String fileName) {
    Preconditions.requireNonNull(content, "content must not be null");
    return Mono.fromCallable(() -> detectNow(content, fileName))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private String detectNow(final byte[] content, final String fileName) {
    final String detected =
        AttachmentSubmission.normalizeContentType(tika.detect(content, fileName));
    return ZIP_FAMILY.contains(detected) ? officeTypeOf(content) : detected;
  }

  private static String officeTypeOf(final byte[] content) {
    return contentTypesOf(content)
        .map(
            declared -> {
              if (declared.contains(DOCX + ".main+xml")) {
                return DOCX;
              }
              if (declared.contains(XLSX + ".main+xml")) {
                return XLSX;
              }
              return ZIP;
            })
        .orElse(ZIP);
  }

  private static Optional<String> contentTypesOf(final byte[] content) {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
      ZipEntry entry = zip.getNextEntry();
      while (entry != null) {
        if (CONTENT_TYPES_ENTRY.equals(entry.getName())) {
          return Optional.of(
              new String(zip.readNBytes(MAX_CONTENT_TYPES_BYTES), StandardCharsets.UTF_8));
        }
        entry = zip.getNextEntry();
      }
      return Optional.empty();
    } catch (final IOException e) {
      return Optional.empty();
    }
  }
}
