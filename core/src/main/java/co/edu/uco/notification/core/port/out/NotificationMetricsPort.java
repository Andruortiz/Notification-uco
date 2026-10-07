package co.edu.uco.notification.core.port.out;

import co.edu.uco.notification.core.domain.valueobject.AttemptResult;
import co.edu.uco.notification.core.domain.valueobject.ChannelType;
import co.edu.uco.notification.core.domain.valueobject.ProviderId;
import co.edu.uco.notification.utils.ErrorCode;
import java.time.Duration;

public interface NotificationMetricsPort {

  void notificationAccepted(ChannelType channel);

  void dispatchAttempted(ChannelType channel, ProviderId provider, AttemptResult result);

  void providerCalled(ProviderId provider, AttemptResult result, Duration duration);

  void errorRecorded(ErrorCode errorCode);

  void errorRecorded(ErrorCode errorCode, ChannelType channel, ProviderId provider);
}
