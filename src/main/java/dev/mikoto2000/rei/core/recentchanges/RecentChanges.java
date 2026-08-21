package dev.mikoto2000.rei.core.recentchanges;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 現在のタスク中に、どのファイルへどのような変更を行ったかを短く保持する。
 *
 * <p>Working Set（どのファイルを使用しているか）や Task State（何をしようとしているか）とは別に、
 * 「現在のタスクで何を変更したか」を保持する。</p>
 */
public class RecentChanges {

  static final int DEFAULT_MAX_ENTRIES = 20;

  private final int maxEntries;
  private final Clock clock;
  private final List<RecentChange> entries = new ArrayList<>();

  public RecentChanges() {
    this(DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
  }

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

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}