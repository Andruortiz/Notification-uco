package co.edu.uco.notification.infrastructure.adapter.out.rabbit;

public record AttachmentScanRequest(String tenantId, String uploadId) {}
