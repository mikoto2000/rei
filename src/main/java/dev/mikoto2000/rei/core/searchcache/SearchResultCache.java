package dev.mikoto2000.rei.core.searchcache;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * 検索結果を TTL 付きで保持するキャッシュ。
 *
 * <p>同一・同等検索を短時間に繰り返すことを減らす。副作用のない検索系ツールの結果のみを対象とする。</p>
 */
public class SearchResultCache {

  static final int DEFAULT_MAX_ENTRIES = 100;
  static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

  private final BoundedTtlCache<SearchCacheKey, Object> entries;

  public SearchResultCache() {
    this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
  }

  public SearchResultCache(Duration ttl, int maxEntries, Clock clock) {
    this.entries = new BoundedTtlCache<>(ttl, Math.max(1, maxEntries), Long.MAX_VALUE, clock, ignored -> 1);
  }

  /** キャッシュが空かどうか。 */
  public boolean isEmpty() {
    return entries.size() == 0;
  }

  /** 現在のエントリ数。 */
  public int size() {
    return entries.size();
  }

  /** 検索結果を保存する。 */
  public void put(SearchCacheKey key, Object result) {
    entries.put(key, result);
  }

  /** 指定キーの有効な検索結果を返す。TTL 超過や失敗結果は空。 */
  public Optional<Object> get(SearchCacheKey key) {
    return entries.get(key);
  }

  /** キャッシュ全体をクリアする。 */
  public void clear() {
    entries.clear();
  }

}
