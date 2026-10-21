package dev.mikoto2000.rei.topic;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 話題の管理に必要な既定エージェント作業状況追跡を扱う。 */
@Component
public class DefaultAgentActivityTracker implements AgentActivityTracker {
  /** アプリケーション開始日時。 */
  private final Instant applicationStartedAt;

  /** 直近利用者作業状況日時。 */
  private final AtomicReference<Instant> lastUserActivityAt;

  /** 直近エージェント作業状況日時。 */
  private final AtomicReference<Instant> lastAgentActivityAt;

  /** エージェント使用中。 */
  private final AtomicBoolean agentBusy = new AtomicBoolean(false);

  /** 作業状況形式のバージョン。 */
  private final AtomicLong activityVersion = new AtomicLong();

  /** 活動中実行一覧。 */
  private org.springframework.beans.factory.ObjectProvider<
          dev.mikoto2000.rei.core.chat.ConversationInputRouter>
      activeRuns;

  /** 話題の管理で使用する活動中実行一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setActiveRuns(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.core.chat.ConversationInputRouter>
          activeRuns) {
    this.activeRuns = activeRuns;
  }

  /** 指定された値や利用先を使って、DefaultAgentActivityTrackerを初期化する。 */
  public DefaultAgentActivityTracker(Clock clock) {
    Instant now = Instant.now(clock);
    applicationStartedAt = now;
    lastUserActivityAt = new AtomicReference<>(now);
    lastAgentActivityAt = new AtomicReference<>(now);
  }

  /** 話題の管理で用いるアプリケーション開始日時を返す。 */
  @Override
  public Instant applicationStartedAt() {
    return applicationStartedAt;
  }

  /** 話題の管理で用いる直近利用者作業状況日時を返す。 */
  @Override
  public Instant lastUserActivityAt() {
    return lastUserActivityAt.get();
  }

  /** 話題の管理で用いる直近エージェント作業状況日時を返す。 */
  @Override
  public Instant lastAgentActivityAt() {
    return lastAgentActivityAt.get();
  }

  /** 話題の管理において、エージェント使用中かどうかを判定する。 */
  @Override
  public boolean isAgentBusy() {
    var router = activeRuns == null ? null : activeRuns.getIfAvailable();
    return agentBusy.get() || (router != null && !router.activeExecutions().isEmpty());
  }

  /** 話題の管理で用いる作業状況形式のバージョンを返す。 */
  @Override
  public long activityVersion() {
    return activityVersion.get();
  }

  /** 後から経緯や使用量を確認できるよう、利用者作業状況を記録する。 */
  @Override
  public void recordUserActivity(Instant at) {
    lastUserActivityAt.set(at);
    activityVersion.incrementAndGet();
  }

  /** 後から経緯や使用量を確認できるよう、エージェント開始を記録する。 */
  @Override
  public void recordAgentStarted(Instant at) {
    agentBusy.set(true);
    lastAgentActivityAt.set(at);
    activityVersion.incrementAndGet();
  }

  /** 後から経緯や使用量を確認できるよう、エージェント完了を記録する。 */
  @Override
  public void recordAgentCompleted(Instant at) {
    agentBusy.set(false);
    lastAgentActivityAt.set(at);
    activityVersion.incrementAndGet();
  }
}
