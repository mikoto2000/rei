package dev.mikoto2000.rei.topic;

/** 利用先の検出確認済みを保存・検索するための窓口。 */
public interface DiscoverySeenRepository {
  /** 話題の管理において、確認済みかどうかを判定する。 */
  boolean isSeen(TopicSource source, String externalId);

  /** 後続の判断に現在の状態を反映するため、確認済みの状態を記録する。 */
  void markSeen(TopicSource source, String externalId);
}
