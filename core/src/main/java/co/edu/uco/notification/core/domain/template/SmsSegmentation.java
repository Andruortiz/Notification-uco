package co.edu.uco.notification.core.domain.template;

public record SmsSegmentation(
    SmsEncoding encoding,
    int units,
    int unitsPerSegment,
    int segments,
    boolean exceedsSingleSegment) {}
