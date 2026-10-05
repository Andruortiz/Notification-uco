package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.configuration.ParameterDescriptor;

public record ParameterDescription(ParameterDescriptor descriptor, long currentValue) {}
