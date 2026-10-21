package dev.mikoto2000.rei.core.filesummary;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ファイルの要約を version と紐付けて保持するキャッシュ。
 *
 * <p>summary はファイル内容の version（sha256 等）と紐付けて保存する。 現在のファイル version と一致しない summary は stale として扱い、LLM
 * には提示しない。
 */
public class FileSummaryCache {

  /** 既定最大項目一覧。 */
  static final int DEFAULT_MAX_ENTRIES = 100;

  /** 最大項目一覧。 */
  private final int maxEntries;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 項目一覧。 */
  private final Map<String, FileSummary> entries = new LinkedHashMap<>();

  /** ファイル内容の要約を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FileSummaryCache() {
    this(DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
  }

  /** ファイル内容の要約を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FileSummaryCache(int maxEntries, Clock clock) {
    this(maxEntries, clock, null, null);
  }

  /** 指定された値や利用先を使って、FileSummaryCacheを初期化する。 */
  public FileSummaryCache(
      int maxEntries, Clock clock, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.maxEntries = Math.max(1, maxEntries);
    this.clock = clock;
    this.events = events;
    this.eventPublisher = eventPublisher;
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
    publish(
        events == null
            ? null
            : events.fileSummarySaved(
                summary.path(), summary.summary() == null ? 0 : summary.summary().length()));
    evictIfNeeded();
  }

  /** 指定パスの要約を返す。 */
  public Optional<FileSummary> find(String path) {
    return Optional.ofNullable(entries.get(path));
  }

  /** 指定パス・version の要約が利用可能（version 一致）かどうか。 */
  public boolean isUsable(String path, String version) {
    return find(path).map(s -> s.version().equals(version)).orElse(false);
  }

  /** 指定パスの要約を無効化（削除）する。 */
  public void invalidate(String path) {
    if (entries.remove(path) != null) {
      publish(events == null ? null : events.fileSummaryInvalidated(path));
    }
  }

  /**
   * LLM コンテキストに渡す表現を組み立てる。
   *
   * <p>versionProvider は各ファイルの現在 version を返す。version が一致しない summary は stale として除外する。
   */
  public String renderForPrompt(java.util.function.Function<String, String> versionProvider) {
    StringBuilder sb = new StringBuilder();
    for (FileSummary summary : entries.values()) {
      String currentVersion = versionProvider.apply(summary.path());
      if (currentVersion == null || !currentVersion.equals(summary.version())) {
        publish(events == null ? null : events.fileSummaryStaleSkipped(summary.path()));
        continue;
      }
      sb.append("- ").append(summary.path()).append("\n");
      sb.append("  ").append(summary.summary()).append("\n");
    }
    return sb.toString();
  }

  /** ファイル内容の要約に関する保持対象からの除外条件付きNeededを処理する。 */
  private void evictIfNeeded() {
    while (entries.size() > maxEntries) {
      String oldest =
          entries.values().stream()
              .min(Comparator.comparing(FileSummary::createdAt))
              .map(FileSummary::path)
              .orElse(null);
      if (oldest == null) {
        return;
      }
      entries.remove(oldest);
    }
  }

  /** ファイル内容の要約で用いる現在日時を返す。 */
  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  /** 状態や結果を購読先へ通知する。 */
  private void publish(dev.mikoto2000.rei.event.AgentEvent event) {
    if (eventPublisher != null && event != null) {
      eventPublisher.publish(event);
    }
  }
}
