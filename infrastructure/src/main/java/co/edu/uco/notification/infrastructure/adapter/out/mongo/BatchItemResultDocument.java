package co.edu.uco.notification.infrastructure.adapter.out.mongo;

public record BatchItemResultDocument(
    String externalId, String outcome, String notificationId, String rejectionReason) {}
