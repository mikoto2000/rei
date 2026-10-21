package dev.mikoto2000.rei.topic;

import java.util.List;

/**
 * 話題の管理で受け渡す話題順位付け文脈を表す。
 *
 * @param recentTopics 直近話題一覧
 */
public record TopicRankingContext(List<String> recentTopics) {
  /** 受け取った情報からTopicRankingContextを作成する。 */
  public TopicRankingContext {
    recentTopics = recentTopics == null ? List.of() : List.copyOf(recentTopics);
  }
}
