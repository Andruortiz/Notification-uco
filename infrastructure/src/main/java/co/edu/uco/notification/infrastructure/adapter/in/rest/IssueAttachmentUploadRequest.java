package co.edu.uco.notification.infrastructure.adapter.in.rest;

public record IssueAttachmentUploadRequest(String fileName, String contentType, Long sizeBytes) {}
