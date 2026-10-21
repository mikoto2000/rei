package dev.mikoto2000.rei.core.recentchanges;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 現在のタスク中に、どのファイルへどのような変更を行ったかを短く保持する。
 *
 * <p>Working Set（どのファイルを使用しているか）や Task State（何をしようとしているか）とは別に、 「現在のタスクで何を変更したか」を保持する。
 */
public class RecentChanges {

  /** 既定最大項目一覧。 */
  static final int DEFAULT_MAX_ENTRIES = 20;

  /** ファイルを作成した。 */
  public static final String OP_CREATE = "CREATE";

  /** ファイルを編集した。 */
  public static final String OP_EDIT = "EDIT";

  /** ファイルを削除した。 */
  public static final String OP_DELETE = "DELETE";

  /** 最大項目一覧。 */
  private final int maxEntries;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 項目一覧。 */
  private final List<RecentChange> entries = new ArrayList<>();

  /** 直近の変更の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RecentChanges() {
    this(DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
  }

  /** 指定された値や利用先を使って、RecentChangesを初期化する。 */
  public RecentChanges(int maxEntries, Clock clock) {
    this.maxEntries = Math.max(1, maxEntries);
    this.clock = clock;
  }

  /** 現在の変更エントリの一覧（新しい順）。 */
  public List<RecentChange> entries() {
    return List.copyOf(entries);
  }

  /** Recent Changes が空かどうか。 */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** 変更を記録する。同じファイルの最新変更は上書きする。 */
  public void record(String path, String operation, String summary) {
    for (int i = entries.size() - 1; i >= 0; i--) {
      if (entries.get(i).path().equals(path)) {
        entries.set(i, new RecentChange(path, operation, summary, now()));
        return;
      }
    }
    entries.add(new RecentChange(path, operation, summary, now()));
    evictIfNeeded();
  }

  /** 直近の変更の把握に関する保持対象からの除外条件付きNeededを処理する。 */
  private void evictIfNeeded() {
    while (entries.size() > maxEntries) {
      entries.remove(0);
    }
  }

  /** 新しいタスク用に Recent Changes をリセットする。 */
  public void reset() {
    entries.clear();
  }

  /** LLM コンテキストに渡す簡潔な表現を組み立てる。 */
  public String renderForPrompt() {
    if (entries.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("## Recent Changes\n\n");
    for (RecentChange entry : entries) {
      sb.append("- ")
          .append(entry.path())
          .append(" [")
          .append(entry.operation().toLowerCase())
          .append("]\n");
      sb.append("  ").append(entry.summary()).append("\n");
    }
    sb.append("\n");
    return sb.toString();
  }

  /** 直近の変更の把握で用いる現在日時を返す。 */
  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
