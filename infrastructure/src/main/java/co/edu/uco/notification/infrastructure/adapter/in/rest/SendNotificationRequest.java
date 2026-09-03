package co.edu.uco.notification.infrastructure.adapter.in.rest;

public record SendNotificationRequest(
    String externalId,
    String channelType,
    String recipientId,
    String recipientAddress,
    String subject,
    String body,
    String priority) {}
