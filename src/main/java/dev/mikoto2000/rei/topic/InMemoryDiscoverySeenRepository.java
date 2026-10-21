package dev.mikoto2000.rei.topic;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 入力先記憶利用先の検出確認済みを保存・検索するための窓口。 */
@Component
public class InMemoryDiscoverySeenRepository implements DiscoverySeenRepository {
  /** 確認済み。 */
  private final Set<String> seen = new LinkedHashSet<>();

  /** 話題の管理において、確認済みかどうかを判定する。 */
  @Override
  public synchronized boolean isSeen(TopicSource source, String externalId) {
    return seen.contains(key(source, externalId));
  }

  /** 後続の判断に現在の状態を反映するため、確認済みの状態を記録する。 */
  @Override
  public synchronized void markSeen(TopicSource source, String externalId) {
    seen.add(key(source, externalId));
  }

  /** 対象を照合・参照するためのキーを返す。 */
  private String key(TopicSource source, String externalId) {
    return source.name() + ":" + (externalId == null ? "" : externalId);
  }
}
