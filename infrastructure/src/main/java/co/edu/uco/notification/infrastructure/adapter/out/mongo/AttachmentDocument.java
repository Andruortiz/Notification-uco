package co.edu.uco.notification.infrastructure.adapter.out.mongo;

public record AttachmentDocument(
    String tenantId,
    String fileName,
    String contentType,
    long sizeBytes,
    String sha256,
    String storage,
    byte[] content,
    String uploadId,
    String objectKey) {

  public static final String EMBEDDED = "EMBEDDED";
  public static final String OBJECT = "OBJECT";

  public AttachmentDocument {
    content = content == null ? null : content.clone();
  }

  @Override
  public byte[] content() {
    return content == null ? null : content.clone();
  }

  @Override
  public String toString() {
    return "AttachmentDocument[fileName="
        + fileName
        + ", contentType="
        + contentType
        + ", sizeBytes="
        + sizeBytes
        + ", storage="
        + storage
        + "]";
  }
}
