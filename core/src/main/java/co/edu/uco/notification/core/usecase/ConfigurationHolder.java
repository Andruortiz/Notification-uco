package co.edu.uco.notification.core.usecase;

import co.edu.uco.notification.core.domain.configuration.ConfigurationSnapshot;
import co.edu.uco.notification.core.port.in.ConfigurationView;
import co.edu.uco.notification.utils.Preconditions;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigurationHolder implements ConfigurationView {

  private final AtomicReference<ConfigurationSnapshot> current;

  public ConfigurationHolder(final ConfigurationSnapshot initial) {
    this.current =
        new AtomicReference<>(Preconditions.requireNonNull(initial, "initial must not be null"));
  }

  @Override
  public ConfigurationSnapshot snapshot() {
    return current.get();
  }

  public void replace(final ConfigurationSnapshot next) {
    current.set(Preconditions.requireNonNull(next, "next must not be null"));
  }

  public boolean compareAndSet(
      final ConfigurationSnapshot expected, final ConfigurationSnapshot next) {
    return current.compareAndSet(
        expected, Preconditions.requireNonNull(next, "next must not be null"));
  }
}
