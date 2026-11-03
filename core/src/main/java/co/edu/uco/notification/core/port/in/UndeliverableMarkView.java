package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import java.time.Instant;

public record UndeliverableMarkView(
    boolean marked, ChannelType channelType, String address, String reason, Instant markedAt) {}
