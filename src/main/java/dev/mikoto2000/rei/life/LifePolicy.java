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

  boolean observationAllowed();

  boolean notificationAllowed(String category, String criterion);

  boolean backgroundAllowed(String project, String task);
}
