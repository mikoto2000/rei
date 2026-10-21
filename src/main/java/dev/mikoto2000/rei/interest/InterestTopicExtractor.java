package dev.mikoto2000.rei.interest;

import java.util.List;

/** 関心話題に必要な情報を入力から取り出す。 */
public interface InterestTopicExtractor {

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  List<InterestTopicCandidate> extract(List<ConversationSnippet> snippets, int maxTopics);

  /**
   * 過去クエリコンテキストを考慮してトピック候補を抽出する。 デフォルト実装は既存の extract(snippets, maxTopics) に委譲する（後方互換性を維持）。
   *
   * @param snippets 会話スニペット一覧
   * @param maxTopics 最大トピック数
   * @param pastQueries 過去 N 日間に使用した検索クエリ一覧（重複回避に使用）
   * @return 抽出されたトピック候補一覧
   */
  default List<InterestTopicCandidate> extract(
      List<ConversationSnippet> snippets, int maxTopics, List<String> pastQueries) {
    return extract(snippets, maxTopics);
  }
}
