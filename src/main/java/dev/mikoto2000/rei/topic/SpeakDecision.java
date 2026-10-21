package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す読み上げ判断結果を表す。
 *
 * @param decision 判断結果
 * @param reason 理由
 * @param selected 選択中
 * @param nextSpeakAllowedAt 次読み上げ許可日時
 */
public record SpeakDecision(
    SpeakDecisionStatus decision,
    TopicSpeakSkipReason reason,
    RankedTopicCandidate selected,
    Instant nextSpeakAllowedAt) {
  /** 話題の管理で用いる読み上げを返す。 */
  public static SpeakDecision speak(RankedTopicCandidate selected, String reason) {
    return new SpeakDecision(SpeakDecisionStatus.SPEAK, null, selected, null);
  }

  /** 話題の管理で用いるdo否定読み上げを返す。 */
  public static SpeakDecision doNotSpeak(TopicSpeakSkipReason reason) {
    return new SpeakDecision(SpeakDecisionStatus.DO_NOT_SPEAK, reason, null, null);
  }

  /** 話題の管理で用いるdo否定読み上げを返す。 */
  public static SpeakDecision doNotSpeak(TopicSpeakSkipReason reason, Instant nextSpeakAllowedAt) {
    return new SpeakDecision(SpeakDecisionStatus.DO_NOT_SPEAK, reason, null, nextSpeakAllowedAt);
  }
}
