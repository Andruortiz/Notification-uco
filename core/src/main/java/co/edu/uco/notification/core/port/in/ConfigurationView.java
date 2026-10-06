package co.edu.uco.notification.core.port.in;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;

public interface ConfigurationView {

  ConfigurationSnapshot snapshot();
}
