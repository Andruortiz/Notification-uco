package co.edu.uco.notification.infrastructure.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

public final class SampleFiles {

  public static final String EICAR =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

  private static final String DOCX_TYPES =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
          + "</Types>";
  private static final String XLSX_TYPES =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
          + "</Types>";

  private SampleFiles() {}

  public static byte[] pdf(final String text) {
    return ("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n% "
            + text
            + "\ntrailer << /Root 1 0 R >>\n%%EOF\n")
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  public static byte[] pdfOfSize(final int size) {
    return pdfOfSize(size, size);
  }

  public static byte[] pdfOfSize(final int size, final long seed) {
    final byte[] header = pdf("padding " + seed);
    final byte[] bytes = new byte[size];
    new Random(seed).nextBytes(bytes);
    System.arraycopy(header, 0, bytes, 0, Math.min(header.length, size));
    return bytes;
  }

  public static byte[] png() {
    return image("png");
  }

  public static byte[] jpeg() {
    return image("jpg");
  }

  public static byte[] text(final String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] executable() {
    final byte[] bytes = new byte[512];
    bytes[0] = 'M';
    bytes[1] = 'Z';
    final byte[] stub =
        "This program cannot be run in DOS mode.".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(stub, 0, bytes, 78, stub.length);
    bytes[60] = (byte) 0x80;
    bytes[0x80] = 'P';
    bytes[0x81] = 'E';
    return bytes;
  }

  public static byte[] docx() {
    return ooxml(DOCX_TYPES, "word/document.xml", "<w:document/>", null, 0);
  }

  public static byte[] xlsx() {
    return ooxml(XLSX_TYPES, "xl/workbook.xml", "<workbook/>", null, 0);
  }

  public static byte[] docxWithEicar(final int minimumSize) {
    return ooxml(DOCX_TYPES, "word/document.xml", "<w:document/>", EICAR, minimumSize);
  }

  public static byte[] docxOfSize(final int minimumSize) {
    return ooxml(DOCX_TYPES, "word/document.xml", "<w:document/>", null, minimumSize);
  }

  private static byte[] image(final String format) {
    try {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, out);
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] ooxml(
      final String contentTypes,
      final String mainPart,
      final String mainXml,
      final String eicar,
      final int minimumSize) {
    try {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(out)) {
        add(zip, "[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
        add(
            zip,
            "_rels/.rels",
            ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\""
                    + mainPart
                    + "\"/></Relationships>")
                .getBytes(StandardCharsets.UTF_8));
        add(zip, mainPart, mainXml.getBytes(StandardCharsets.UTF_8));
        if (eicar != null) {
          add(zip, "eicar.com", eicar.getBytes(StandardCharsets.US_ASCII));
        }
        if (minimumSize > 0) {
          final byte[] padding = new byte[minimumSize];
          new Random(minimumSize).nextBytes(padding);
          final ZipEntry entry = new ZipEntry("media/padding.bin");
          entry.setMethod(ZipEntry.STORED);
          entry.setSize(padding.length);
          final CRC32 crc = new CRC32();
          crc.update(padding);
          entry.setCrc(crc.getValue());
          zip.putNextEntry(entry);
          zip.write(padding);
          zip.closeEntry();
        }
      }
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void add(final ZipOutputStream zip, final String name, final byte[] content)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }
}
