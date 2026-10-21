package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 既定操作がない状態話題実行条件の結果と状態の契約を検証するテスト。 */
class DefaultIdleTopicTriggerTest {

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void acceptsWhenFeatureEnabledAgentFreeAndIdleLongEnough() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofMinutes(10)));
    tracker.lastUserActivityAt = now().minus(Duration.ofMinutes(3));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(4));

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertTrue(decision.accepted());
    assertEquals(Duration.ofMinutes(3), decision.idleDuration());
  }

  /** 話題の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsWhenRecentUserActivityIsInsideMinimumIdle() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofMinutes(10)));
    tracker.lastUserActivityAt = now().minus(Duration.ofSeconds(30));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(4));

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertFalse(decision.accepted());
    assertEquals(IdleTriggerRejectReason.INSUFFICIENT_IDLE, decision.rejectReason());
  }

  /** 話題の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsWhenAgentIsBusy() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofMinutes(10)));
    tracker.lastUserActivityAt = now().minus(Duration.ofMinutes(3));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(4));
    tracker.agentBusy = true;

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertFalse(decision.accepted());
    assertEquals(IdleTriggerRejectReason.AGENT_BUSY, decision.rejectReason());
  }

  /** 話題の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsWhenApplicationStartupIsStillRecent() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofSeconds(20)));
    tracker.lastUserActivityAt = now().minus(Duration.ofMinutes(3));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(3));

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertFalse(decision.accepted());
    assertEquals(IdleTriggerRejectReason.INSUFFICIENT_IDLE, decision.rejectReason());
  }

  /** {@code enabledProperties} の条件を再現するための補助情報を返す。 */
  private TopicGeneratorProperties enabledProperties() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    properties.getIdleTrigger().setEnabled(true);
    properties.getIdleTrigger().setMinimumIdle(Duration.ofMinutes(2));
    return properties;
  }

  /** {@code now} の条件を再現するための補助情報を返す。 */
  private Instant now() {
    return Instant.parse("2026-09-02T00:10:00Z");
  }

  /** 検証条件を再現するための検証作業状況追跡。 */
  private static class TestActivityTracker implements AgentActivityTracker {
    /** 検証に使うアプリケーション開始日時。 */
    private final Instant applicationStartedAt;

    /** 検証に使う直近利用者作業状況日時。 */
    private Instant lastUserActivityAt;

    /** 検証に使う直近エージェント作業状況日時。 */
    private Instant lastAgentActivityAt;

    /** 検証に使うエージェント使用中。 */
    private boolean agentBusy;

    /** 検証条件に必要な値を受け取り、TestActivityTrackerを用意する。 */
    TestActivityTracker(Instant applicationStartedAt) {
      this.applicationStartedAt = applicationStartedAt;
      this.lastUserActivityAt = applicationStartedAt;
      this.lastAgentActivityAt = applicationStartedAt;
    }

    /** {@code applicationStartedAt} の条件を再現するための補助情報を返す。 */
    @Override
    public Instant applicationStartedAt() {
      return applicationStartedAt;
    }

    /** {@code lastUserActivityAt} の条件を再現するための補助情報を返す。 */
    @Override
    public Instant lastUserActivityAt() {
      return lastUserActivityAt;
    }

    /** {@code lastAgentActivityAt} の条件を再現するための補助情報を返す。 */
    @Override
    public Instant lastAgentActivityAt() {
      return lastAgentActivityAt;
    }

    /** 検証用に、話題の管理において、エージェント使用中かどうかを判定する。 */
    @Override
    public boolean isAgentBusy() {
      return agentBusy;
    }

    /** {@code activityVersion} の条件を再現するための補助情報を返す。 */
    @Override
    public long activityVersion() {
      return 0;
    }

    /** 検証用に、後から経緯や使用量を確認できるよう、利用者作業状況を記録する。 */
    @Override
    public void recordUserActivity(Instant at) {
      lastUserActivityAt = at;
    }

    /** 検証用に、後から経緯や使用量を確認できるよう、エージェント開始を記録する。 */
    @Override
    public void recordAgentStarted(Instant at) {
      lastAgentActivityAt = at;
      agentBusy = true;
    }

    /** 検証用に、後から経緯や使用量を確認できるよう、エージェント完了済みのを記録する。 */
    @Override
    public void recordAgentCompleted(Instant at) {
      lastAgentActivityAt = at;
      agentBusy = false;
    }
  }
}
