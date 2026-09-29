package co.edu.uco.notification.infrastructure.adapter.out.mongo;

public record AttachmentDocument(String fileName, String contentType, Long sizeBytes, String url) {}
