package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 入力先記憶話題候補の記録を保持し、後続の処理で参照できるようにする。 */
public class InMemoryTopicCandidateStore implements TopicCandidateStore {
  /** 候補一覧。 */
  private List<TopicCandidate> candidates = List.of();

  /** 生成済み日時。 */
  private Instant generatedAt;

  /** 指定された内容へ切り替えるため、入力先記憶話題候補保存先を置き換える。 */
  @Override
  public synchronized void replace(List<TopicCandidate> candidates, Instant generatedAt) {
    this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    this.generatedAt = generatedAt;
  }

  /** 話題の管理で用いる現在候補一覧を返す。 */
  @Override
  public synchronized List<TopicCandidate> currentCandidates(Instant now, Duration maxAge) {
    if (generatedAt == null || candidates.isEmpty()) return List.of();
    if (maxAge != null && Duration.between(generatedAt, now).compareTo(maxAge) > 0)
      return List.of();
    return candidates;
  }
}
