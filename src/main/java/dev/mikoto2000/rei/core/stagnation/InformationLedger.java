package dev.mikoto2000.rei.core.stagnation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 実行の停滞への対処に必要な情報記録台帳を扱う。
 *
 * <p>Bounded exact evidence. Retained identities are never evicted or promoted twice.
 */
public final class InformationLedger {
  /** 上限。 */
  static final int LIMIT = 1024;

  /** 実行の停滞への対処における状態の選択肢。 */
  public enum Status {
    /** 新規。 */
    NEW,
    /** 重複の有無。 */
    DUPLICATE,
    /** 不明。 */
    UNKNOWN
  }

  /**
   * 実行の停滞への対処で受け渡す観測内容を表す。
   *
   * @param status 状態
   * @param newLines 新規行一覧
   */
  public record Observation(Status status, long newLines) {}

  /**
   * 実行の停滞への対処で受け渡す行対象範囲を表す。
   *
   * @param startInclusive 開始Inclusive
   * @param endExclusive 終了排他
   */
  public record LineRange(int startInclusive, int endExclusive) {
    /** 受け取った情報からLineRangeを作成する。 */
    public LineRange {
      if (startInclusive < 1 || endExclusive <= startInclusive)
        throw new IllegalArgumentException("Invalid range");
    }
  }

  /**
   * 実行の停滞への対処で受け渡すファイル読み取り進捗を表す。
   *
   * @param normalizedPath 表記を統一したパス
   * @param revision 改訂番号
   * @param ranges 対象範囲一覧
   */
  public record FileReadProgress(String normalizedPath, String revision, List<LineRange> ranges) {
    /** 受け取った情報からFileReadProgressを作成する。 */
    public FileReadProgress {
      ranges = List.copyOf(ranges);
    }
  }

  /**
   * 実行の停滞への対処で受け渡す状態を表す。
   *
   * @param files ファイル一覧
   * @param identities 識別情報一覧
   * @param historicalUnknown historical不明
   */
  public record State(
      List<FileReadProgress> files, Set<String> identities, boolean historicalUnknown) {
    /** 受け取った情報からStateを作成する。 */
    public State {
      files = List.copyOf(files);
      identities = Set.copyOf(identities);
    }
  }

  /** ファイル一覧。 */
  private final Map<String, FileReadProgress> files = new LinkedHashMap<>();

  /** 識別情報一覧。 */
  private final Set<String> identities = new HashSet<>();

  /** 対象範囲件数。 */
  private int rangeCount;

  /** 取得元文字数。 */
  private int sourceCharacters;

  /** historical不明。 */
  private boolean historicalUnknown;

  /** 状況を判断する根拠を得るため、情報記録台帳を観測する。 */
  public synchronized Status observe(String identity) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (identity == null || identity.length() > 64) return Status.UNKNOWN;
    if (identities.contains(identity)) return Status.DUPLICATE;
    if (historicalUnknown || identities.size() >= LIMIT) return Status.UNKNOWN;
    identities.add(identity);
    return Status.NEW;
  }

  /** 状況を判断する根拠を得るため、対象範囲を観測する。 */
  public synchronized Observation observeRange(String path, String revision, int start, int end) {
    if (path == null
        || revision == null
        || path.length() > 4096
        || revision.length() > 256
        || start < 1
        || end <= start) return new Observation(Status.UNKNOWN, 0);
    String key = path + "\u0000" + revision;
    var previous = files.get(key);
    var old = previous == null ? List.<LineRange>of() : previous.ranges();
    long added = (long) end - start;
    for (var range : old)
      added -=
          Math.max(
              0L,
              (long) Math.min(end, range.endExclusive()) - Math.max(start, range.startInclusive()));
    if (added == 0) return new Observation(Status.DUPLICATE, 0);
    if (historicalUnknown) return new Observation(Status.UNKNOWN, 0);
    var sorted = new ArrayList<>(old);
    sorted.add(new LineRange(start, end));
    sorted.sort(Comparator.comparingInt(LineRange::startInclusive));
    var merged = new ArrayList<LineRange>();
    for (var range : sorted) {
      if (merged.isEmpty() || merged.getLast().endExclusive() < range.startInclusive())
        merged.add(range);
      else {
        var last = merged.removeLast();
        merged.add(
            new LineRange(
                last.startInclusive(), Math.max(last.endExclusive(), range.endExclusive())));
      }
    }
    int count = rangeCount - old.size() + merged.size();
    if (previous == null
            && (files.size() >= LIMIT
                || sourceCharacters + path.length() + revision.length() > 32768)
        || count > LIMIT) return new Observation(Status.UNKNOWN, 0);
    if (previous == null) sourceCharacters += path.length() + revision.length();
    files.put(key, new FileReadProgress(path, revision, merged));
    rangeCount = count;
    return new Observation(Status.NEW, added);
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  public synchronized State snapshot() {
    return new State(new ArrayList<>(files.values()), identities, historicalUnknown);
  }

  /** 実行の停滞への対処において、coversに該当するか判定する。 */
  public synchronized boolean covers(String path, String revision, int start, int end) {
    var file = files.get(path + "\u0000" + revision);
    return file != null
        && file.ranges().stream()
            .anyMatch(r -> r.startInclusive() <= start && r.endExclusive() >= end);
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
  public synchronized void restore(State state) {
    if (state == null) {
      files.clear();
      identities.clear();
      rangeCount = 0;
      sourceCharacters = 0;
      historicalUnknown = true;
      return;
    }
    if (state.files().size() > LIMIT
        || state.identities().size() > LIMIT
        || state.identities().stream().anyMatch(s -> s.length() > 64))
      throw new IllegalArgumentException("Progress state capacity exceeded");
    // Validate atomically in a separate ledger before replacing live state.
    var validated = new InformationLedger();
    for (var file : state.files())
      for (var range : file.ranges()) {
        var observed =
            validated.observeRange(
                file.normalizedPath(),
                file.revision(),
                range.startInclusive(),
                range.endExclusive());
        if (observed.status() == Status.UNKNOWN)
          throw new IllegalArgumentException("Invalid progress state");
      }
    files.clear();
    identities.clear();
    files.putAll(validated.files);
    rangeCount = validated.rangeCount;
    sourceCharacters = validated.sourceCharacters;
    identities.addAll(state.identities());
    historicalUnknown = state.historicalUnknown();
  }
}
