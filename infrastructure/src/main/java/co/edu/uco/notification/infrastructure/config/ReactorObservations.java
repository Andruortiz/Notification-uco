package co.edu.uco.notification.infrastructure.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.function.Supplier;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

public final class ReactorObservations {

  private ReactorObservations() {}

  public static Observation from(final ContextView context) {
    final Object value = context.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
    return value instanceof Observation observation ? observation : null;
  }

  public static Context with(final Context context, final Observation observation) {
    return observation == null
        ? context
        : context.put(ObservationThreadLocalAccessor.KEY, observation);
  }

  public static void run(final ContextView context, final Runnable action) {
    call(
        context,
        () -> {
          action.run();
          return null;
        });
  }

  public static <T> T call(final ContextView context, final Supplier<T> action) {
    final Observation observation = from(context);
    if (observation == null) {
      return action.get();
    }
    try (Observation.Scope ignored = observation.openScope()) {
      return action.get();
    }
  }
}
