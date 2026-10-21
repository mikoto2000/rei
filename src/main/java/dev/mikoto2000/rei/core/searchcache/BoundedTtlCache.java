package dev.mikoto2000.rei.core.searchcache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * 検索結果の再利用に必要な上限内に収めた有効期間キャッシュを扱う。
 *
 * <p>Thread-safe FIFO with finite retention and both entry and resident-weight bounds.
 */
public final class BoundedTtlCache<K, V> {
  /**
   * 検索結果の再利用で受け渡す項目を表す。
   *
   * @param value 値
   * @param weight 重み
   * @param expiresAt 有効期限
   * @param createdNanos 作成ナノ秒数
   */
  private record Entry<V>(V value, long weight, Instant expiresAt, long createdNanos) {}

  /** 有効期間。 */
  private final Duration ttl;

  /** 有効期間ナノ秒数。 */
  private final long ttlNanos;

  /** 最大項目一覧。 */
  private final int maximumEntries;

  /** 最大バイト数。 */
  private final long maximumBytes;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 保持する情報量の計算方法。 */
  private final ToLongFunction<? super V> weigher;

  /** 項目一覧。 */
  private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();

  /** バイト数。 */
  private long bytes;

  /** 指定された値や利用先を使って、BoundedTtlCacheを初期化する。 */
  public BoundedTtlCache(
      Duration ttl,
      int maximumEntries,
      long maximumBytes,
      Clock clock,
      ToLongFunction<? super V> weigher) {
    if (ttl == null || ttl.isNegative() || ttl.isZero() || maximumEntries < 1 || maximumBytes < 1)
      throw new IllegalArgumentException("Invalid cache bounds");
    this.ttl = ttl;
    this.maximumEntries = maximumEntries;
    this.maximumBytes = maximumBytes;
    this.clock = Objects.requireNonNull(clock);
    this.weigher = Objects.requireNonNull(weigher);
    ttlNanos =
        ttl.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : ttl.toNanos();
  }

  /** 検索結果の再利用において、登録に該当するか判定する。 */
  public synchronized boolean put(K key, V value) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(value);
    long weight = weigher.applyAsLong(value);
    if (weight < 0) throw new IllegalArgumentException("Negative cache weight");
    if (weight > maximumBytes) return false;
    purge();
    remove(key);
    while (!entries.isEmpty()
        && (entries.size() >= maximumEntries || weight > maximumBytes - bytes)) {
      remove(entries.keySet().iterator().next());
    }
    entries.put(key, new Entry<>(value, weight, clock.instant().plus(ttl), System.nanoTime()));
    bytes += weight;
    return true;
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public synchronized Optional<V> get(K key) {
    Entry<V> entry = entries.get(key);
    if (entry == null) return Optional.empty();
    if (expired(entry)) {
      remove(key);
      return Optional.empty();
    }
    return Optional.of(entry.value());
  }

  /** 指定された対象を管理から取り除く。 */
  public synchronized void remove(K key) {
    var removed = entries.remove(key);
    if (removed != null) bytes -= removed.weight();
  }

  /** 保持している情報を初期状態へ戻すため、上限内に収めた有効期間キャッシュを消去する。 */
  public synchronized void clear() {
    entries.clear();
    bytes = 0;
  }

  /** 検索結果の再利用で用いる大きさを返す。 */
  public synchronized int size() {
    purge();
    return entries.size();
  }

  /** 検索結果の再利用で用いるバイト数を返す。 */
  public synchronized long bytes() {
    purge();
    return bytes;
  }

  /** 検索結果の再利用において、期限切れに該当するか判定する。 */
  private boolean expired(Entry<V> entry) {
    return !clock.instant().isBefore(entry.expiresAt())
        || System.nanoTime() - entry.createdNanos() >= ttlNanos;
  }

  /** 検索結果の再利用に関する保持期限を過ぎた情報の削除を処理する。 */
  private void purge() {
    var iterator = entries.values().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (expired(entry)) {
        bytes -= entry.weight();
        iterator.remove();
      }
    }
  }
}
