package co.edu.uco.notification.core.domain.valueobject;

import co.edu.uco.notification.utils.Preconditions;
import java.util.Arrays;

public sealed interface AttachmentSource
    permits AttachmentSource.EmbeddedContent, AttachmentSource.StoredObject {

  record EmbeddedContent(byte[] bytes) implements AttachmentSource {

    public EmbeddedContent {
      Preconditions.requireNonNull(bytes, "bytes must not be null");
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof EmbeddedContent that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
      return "EmbeddedContent[length=" + bytes.length + "]";
    }
  }

  record StoredObject(UploadId uploadId, String objectKey) implements AttachmentSource {

    public StoredObject {
      Preconditions.requireNonNull(uploadId, "uploadId must not be null");
      Preconditions.requireNonBlank(objectKey, "objectKey must not be blank");
    }

    @Override
    public String toString() {
      return "StoredObject[uploadId=" + uploadId.value() + "]";
    }
  }
}
