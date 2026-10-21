package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

/**
 * 話題の管理で受け渡す読み上げ判断結果文脈を表す。
 *
 * @param currentTime 現在時刻
 * @param lastTopicSpokenAt 直近話題読み上げ済み日時
 * @param recentTopics 直近話題一覧
 * @param userRecentlyActive 利用者Recently活動中
 * @param agentBusy エージェント使用中
 */
public record SpeakDecisionContext(
    Instant currentTime,
    Instant lastTopicSpokenAt,
    List<String> recentTopics,
    boolean userRecentlyActive,
    boolean agentBusy) {

  /** 受け取った情報からSpeakDecisionContextを作成する。 */
  public SpeakDecisionContext {
    if (currentTime == null) throw new IllegalArgumentException("currentTime must not be null");
    recentTopics = recentTopics == null ? List.of() : List.copyOf(recentTopics);
  }
}
