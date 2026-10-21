package dev.mikoto2000.rei.topic;

import java.util.List;

/** 話題の管理に必要な話題Rankerを扱う。 */
public interface TopicRanker {
  /** 話題の管理で用いる順位を返す。 */
  List<RankedTopicCandidate> rank(List<TopicCandidate> candidates, TopicRankingContext context);
}
