package dev.mikoto2000.rei.interest;

import java.util.List;

public interface InterestTopicExtractor {

  List<InterestTopicCandidate> extract(List<ConversationSnippet> snippets, int maxTopics);

  /**
   * 過去クエリコンテキストを考慮してトピック候補を抽出する。
   * デフォルト実装は既存の extract(snippets, maxTopics) に委譲する（後方互換性を維持）。
   *
   * @param snippets    会話スニペット一覧
   * @param maxTopics   最大トピック数
   * @param pastQueries 過去 N 日間に使用した検索クエリ一覧（重複回避に使用）
   * @return 抽出されたトピック候補一覧
   */
  default List<InterestTopicCandidate> extract(
      List<ConversationSnippet> snippets,
      int maxTopics,
      List<String> pastQueries) {
    return extract(snippets, maxTopics);
  }
}
