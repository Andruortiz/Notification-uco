package co.edu.uco.notification.infrastructure.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BrevoBounceEvent(String event, @JsonProperty("message-id") String messageId) {}
