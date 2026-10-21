package dev.mikoto2000.rei.http.cache;

/** HTTP通信キャッシュの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@org.springframework.stereotype.Component
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "rei.http-cache")
@lombok.Getter
@lombok.Setter
public class HttpCacheProperties {
  /** 有効状態。 */
  private boolean enabled = true;

  /** 検索有効期間秒数。 */
  private int searchTtlSeconds = 30;

  /** ページ有効期間秒数。 */
  private int pageTtlSeconds = 60;

  /** 保持期間秒数。 */
  private int retentionSeconds = 300;

  /** 最大項目一覧。 */
  private int maxEntries = 128;

  /** バイト数の上限。 */
  private long maxBytes = 32L * 1024 * 1024;

  /** 読み込みParallelism。 */
  private int loadParallelism = 3;

  /** 読み込み実行待ち許容件数。 */
  private int loadQueueCapacity = 16;

  /** 最大入力先Flight。 */
  private int maxInFlight = 32;

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (searchTtlSeconds < 1
        || searchTtlSeconds > 300
        || pageTtlSeconds < 1
        || pageTtlSeconds > 300
        || retentionSeconds < Math.max(searchTtlSeconds, pageTtlSeconds)
        || retentionSeconds > 1800
        || maxEntries < 1
        || maxEntries > 1024
        || maxBytes < 1
        || maxBytes > 128L * 1024 * 1024
        || loadParallelism < 1
        || loadParallelism > 3
        || loadQueueCapacity < 1
        || loadQueueCapacity > 64
        || maxInFlight < 1
        || maxInFlight > 128) throw new IllegalArgumentException("Invalid HTTP cache limits");
  }
}
