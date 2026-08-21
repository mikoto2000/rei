package dev.mikoto2000.rei.core.searchcache;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 検索結果を TTL 付きで保持するキャッシュ。
 *
 * <p>同一・同等検索を短時間に繰り返すことを減らす。副作用のない検索系ツールの結果のみを対象とする。</p>
 */
public class SearchResultCache {

  static final int DEFAULT_MAX_ENTRIES = 100;
  static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

  private final Duration ttl;
  private final int maxEntries;
  private final Clock clock;
  private final Map<SearchCacheKey, Entry> entries = new LinkedHashMap<>();

  public SearchResultCache() {
    this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
  }

  public SearchResultCache(Duration ttl, int maxEntries, Clock clock) {
    this.ttl = ttl;
    this.maxEntries = Math.max(1, maxEntries);
    this.clock = clock;
  }

  /** キャッシュが空かどうか。 */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** 現在のエントリ数。 */
  public int size() {
    return entries.size();
  }

  /** 検索結果を保存する。 */
  public void put(SearchCacheKey key, Object result) {
    entries.put(key, new Entry(result, now()));
    evictIfNeeded();
  }

  /** 指定キーの有効な検索結果を返す。TTL 超過や失敗結果は空。 */
  public Optional<Object> get(SearchCacheKey key) {
    Entry entry = entries.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (now().isAfter(entry.createdAt().plus(ttl))) {
      entries.remove(key);
      return Optional.empty();
    }
    return Optional.of(entry.result());
  }

  /** キャッシュ全体をクリアする。 */
  public void clear() {
    entries.clear();
  }

  private void evictIfNeeded() {
    while (entries.size() > maxEntries) {
      Map.Entry<SearchCacheKey, Entry> oldest = entries.entrySet().iterator().next();
      entries.remove(oldest.getKey());
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private record Entry(Object result, OffsetDateTime createdAt) {
  }
}