package dev.mikoto2000.rei.topic;

import java.util.List;

/** 読み上げ判断結果の判断基準をまとめる。 */
public interface SpeakDecisionPolicy {
  /** 話題の管理で用いる判断を返す。 */
  SpeakDecision decide(List<RankedTopicCandidate> candidates, SpeakDecisionContext context);
}
