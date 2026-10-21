package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 話題候補の記録を保持し、後続の処理で参照できるようにする。 */
public interface TopicCandidateStore {
  /** 指定された内容へ切り替えるため、話題候補保存先を置き換える。 */
  void replace(List<TopicCandidate> candidates, Instant generatedAt);

  /** 話題の管理で用いる現在候補一覧を返す。 */
  List<TopicCandidate> currentCandidates(Instant now, Duration maxAge);
}
