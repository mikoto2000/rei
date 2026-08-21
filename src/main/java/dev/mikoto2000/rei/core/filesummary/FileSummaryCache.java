package dev.mikoto2000.rei.core.filesummary;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ファイルの要約を version と紐付けて保持するキャッシュ。
 *
 * <p>summary はファイル内容の version（sha256 等）と紐付けて保存する。
 * 現在のファイル version と一致しない summary は stale として扱い、LLM には提示しない。</p>
 */
public class FileSummaryCache {

  static final int DEFAULT_MAX_ENTRIES = 100;

  private final int maxEntries;
  private final Clock clock;
  private final Map<String, FileSummary> entries = new LinkedHashMap<>();

  public FileSummaryCache() {
    this(DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
  }

  public FileSummaryCache(int maxEntries, Clock clock) {
    this.maxEntries = Math.max(1, maxEntries);
    this.clock = clock;
  }

  /** 現在のキャッシュエントリ一覧（新しい順）。 */
  public List<FileSummary> entries() {
    return List.copyOf(entries.values());
  }

  /** キャッシュが空かどうか。 */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** 最大件数。 */
  public int maxEntries() {
    return maxEntries;
  }

  /** ファイル要約を保存する。 */
  public void save(FileSummary summary) {
    entries.put(summary.path(), summary);
    evictIfNeeded();
  }

  private void evictIfNeeded() {
    while (entries.size() > maxEntries) {
      String oldest = entries.values().stream()
          .min(Comparator.comparing(FileSummary::createdAt))
          .map(FileSummary::path)
          .orElse(null);
      if (oldest == null) {
        return;
      }
      entries.remove(oldest);
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
