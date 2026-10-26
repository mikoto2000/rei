package dev.mikoto2000.rei.life;

import java.time.Instant;

/** 観測・通知・実行側に設定変更権限を渡さない読み取り専用の制約窓口。 */
public interface LifePolicy {
  LifeContext.Settings settings();

  LifeContext.Status status();

  default long availableMinutes() {
    return 0;
  }

  LifeContext.Lease begin();

  boolean valid(LifeContext.Lease lease);

  boolean observationAllowed(Instant since);

  /** Explicit microphone input may ignore work hours, while retaining other privacy constraints. */
  default boolean manualObservationAllowed(Instant since) {
    return observationAllowed(since);
  }

  default boolean validManualObservation(LifeContext.Lease lease) {
    return valid(lease);
  }

  boolean observationAllowed();

  boolean notificationAllowed(String category, String criterion);

  boolean backgroundAllowed(String project, String task);
}
