package co.edu.uco.notification.infrastructure.adapter.out.mongo;

import java.util.Arrays;
import java.util.Objects;

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
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AttachmentDocument that)) {
      return false;
    }
    return sizeBytes == that.sizeBytes
        && Objects.equals(tenantId, that.tenantId)
        && Objects.equals(fileName, that.fileName)
        && Objects.equals(contentType, that.contentType)
        && Objects.equals(sha256, that.sha256)
        && Objects.equals(storage, that.storage)
        && Arrays.equals(content, that.content)
        && Objects.equals(uploadId, that.uploadId)
        && Objects.equals(objectKey, that.objectKey);
  }

  @Override
  public int hashCode() {
    return 31
            * Objects.hash(
                tenantId, fileName, contentType, sizeBytes, sha256, storage, uploadId, objectKey)
        + Arrays.hashCode(content);
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
