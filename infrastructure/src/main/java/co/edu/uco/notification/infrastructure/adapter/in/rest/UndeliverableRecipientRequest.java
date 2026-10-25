package co.edu.uco.notification.infrastructure.adapter.in.rest;

public record UndeliverableRecipientRequest(String channel, String address) {}
