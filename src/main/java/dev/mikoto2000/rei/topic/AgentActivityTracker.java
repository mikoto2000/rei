package dev.mikoto2000.rei.topic;

import java.time.Instant;

/** 話題の管理に必要なエージェント作業状況追跡を扱う。 */
public interface AgentActivityTracker {
  /** 話題の管理で用いるアプリケーション開始日時を返す。 */
  Instant applicationStartedAt();

  /** 話題の管理で用いる直近利用者作業状況日時を返す。 */
  Instant lastUserActivityAt();

  /** 話題の管理で用いる直近エージェント作業状況日時を返す。 */
  Instant lastAgentActivityAt();

  /** 話題の管理において、エージェント使用中かどうかを判定する。 */
  boolean isAgentBusy();

  /** 話題の管理で用いる作業状況形式のバージョンを返す。 */
  long activityVersion();

  /** 後から経緯や使用量を確認できるよう、利用者作業状況を記録する。 */
  void recordUserActivity(Instant at);

  /** 後から経緯や使用量を確認できるよう、エージェント開始を記録する。 */
  void recordAgentStarted(Instant at);

  /** 後から経緯や使用量を確認できるよう、エージェント完了を記録する。 */
  void recordAgentCompleted(Instant at);
}
