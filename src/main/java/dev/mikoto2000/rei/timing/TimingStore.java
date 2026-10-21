package dev.mikoto2000.rei.timing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 時間計測の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Bounded process-local observation. Missing/late records never change execution results.
 */
@Service
public class TimingStore implements TimingRecorder {
  /** 有効状態。 */
  private final boolean enabled;

  /** 最大実行一覧／最大ごとの実行／最大合計。 */
  private final int maxRuns, maxPerRun, maxTotal;

  /** 有効期間ナノ秒数。 */
  private final long ttlNanos;

  /** 実際の日時の取得元または記録値。 */
  private final Clock wall;

  /** ナノ秒数。 */
  private final LongSupplier nanos;

  /** 実行一覧。 */
  private final LinkedHashMap<String, Entry> runs = new LinkedHashMap<>();

  /** 計測区間件数。 */
  private int spanCount;

  /** 保持対象から除外した件数と、記録を受け付けずに破棄した件数。 */
  private long evictions, drops;

  /** 実行時間の把握に必要な項目を扱う。 */
  private static final class Entry {
    /** 識別子／プロジェクト／会話。 */
    final String id, project, session;

    /** 実際の日時の取得元または記録値。 */
    final Instant wall;

    /** 開始。 */
    final long start;

    /** 終了。 */
    Long end;

    /** 状態。 */
    Status status = Status.INCOMPLETE;

    /** 未指定。 */
    boolean missing;

    /** 接頭辞。 */
    final PrefixDiagnostics prefix = new PrefixDiagnostics();

    /** 子の一覧。 */
    final LinkedHashMap<String, Child> children = new LinkedHashMap<>();

    /** 達成の節目。 */
    final EnumMap<Metric, Long> milestones = new EnumMap<>(Metric.class);

    /** 指定された値や利用先を使って、Entryを初期化する。 */
    Entry(String id, String project, String session, Instant wall, long start) {
      this.id = id;
      this.project = project;
      this.session = session;
      this.wall = wall;
      this.start = start;
    }
  }

  /** 実行時間の把握に必要な子を扱う。 */
  private static final class Child {
    /** 識別子／親／要求／試行。 */
    final String id, parent, request, attempt;

    /** 分類。 */
    final Category category;

    /** 実際の日時の取得元または記録値。 */
    final Instant wall;

    /** 開始。 */
    final long start;

    /** 終了。 */
    Long end;

    /** 状態。 */
    Status status = Status.INCOMPLETE;

    /** 入力／出力／生成済み／生成。 */
    Long input, output, generated, generation;

    /** 言語モデル。 */
    LlmObservation llm;

    /** 達成の節目。 */
    final EnumMap<Metric, Long> milestones = new EnumMap<>(Metric.class);

    /** 指定された値や利用先を使って、Childを初期化する。 */
    Child(
        String id,
        String parent,
        String request,
        String attempt,
        Category category,
        Instant wall,
        long start) {
      this.id = id;
      this.parent = parent;
      this.request = request;
      this.attempt = attempt;
      this.category = category;
      this.wall = wall;
      this.start = start;
    }

    /** 現在の状態を、後から参照できる記録として取り出す。 */
    Span snapshot() {
      return new Span(
          id,
          parent,
          request,
          attempt,
          category,
          status,
          wall,
          start,
          end,
          milestones,
          input,
          output,
          generated,
          generation,
          llm);
    }
  }

  /** 実行時間の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public TimingStore(Environment environment, Clock wall) {
    this(
        environment.getProperty("rei.timing.enabled", Boolean.class, true),
        environment.getProperty("rei.timing.enabled", Boolean.class, true)
            ? environment.getProperty("rei.timing.max-runs", Integer.class, 100)
            : 100,
        environment.getProperty("rei.timing.enabled", Boolean.class, true)
            ? environment.getProperty("rei.timing.max-spans-per-run", Integer.class, 2000)
            : 2000,
        environment.getProperty("rei.timing.enabled", Boolean.class, true)
            ? environment.getProperty("rei.timing.max-total-spans", Integer.class, 10000)
            : 10000,
        environment.getProperty("rei.timing.enabled", Boolean.class, true)
            ? org.springframework.boot.convert.DurationStyle.detectAndParse(
                environment.getProperty("rei.timing.ttl", "1h"))
            : Duration.ofHours(1),
        wall,
        System::nanoTime);
  }

  /** 指定された値や利用先を使って、TimingStoreを初期化する。 */
  public TimingStore(
      boolean enabled,
      int maxRuns,
      int maxPerRun,
      int maxTotal,
      Duration ttl,
      Clock wall,
      LongSupplier nanos) {
    if (maxRuns < 1
        || maxRuns > 500
        || maxPerRun < 1
        || maxPerRun > 10000
        || maxTotal < maxPerRun
        || maxTotal > 50000
        || ttl == null
        || ttl.compareTo(Duration.ofSeconds(1)) < 0
        || ttl.compareTo(Duration.ofDays(1)) > 0)
      throw new IllegalArgumentException("Timing limits out of range");
    this.enabled = enabled;
    this.maxRuns = maxRuns;
    this.maxPerRun = maxPerRun;
    this.maxTotal = maxTotal;
    this.ttlNanos = ttl.toNanos();
    this.wall = Objects.requireNonNull(wall);
    this.nanos = Objects.requireNonNull(nanos);
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  @Override
  public boolean enabled() {
    return enabled;
  }

  /** 識別子。 */
  private static final java.util.regex.Pattern ID =
      java.util.regex.Pattern.compile("[A-Za-z0-9._:-]+");

  /** 実行時間の把握において、識別子に該当するか判定する。 */
  private static boolean id(String value) {
    return value != null && value.length() <= 128 && ID.matcher(value).matches();
  }

  /** 実行時間の把握において、任意識別子に該当するか判定する。 */
  private static boolean optionalId(String value) {
    return value == null || id(value);
  }

  /** 実行時間の把握に関する保持期限を過ぎた情報の削除を処理する。 */
  private void purge(long now) {
    var iterator = runs.values().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (now - entry.start >= ttlNanos) {
        spanCount -= entry.children.size();
        iterator.remove();
        evictions++;
      }
    }
  }

  /** 実行時間の把握に関する保持対象からの除外を処理する。 */
  private void evict(String runId) {
    var removed = runs.remove(runId);
    if (removed != null) {
      spanCount -= removed.children.size();
      evictions++;
    }
  }

  /** 実行時間の把握において、開始実行に該当するか判定する。 */
  @Override
  public synchronized boolean beginRun(String runId, String projectId, String sessionId) {
    if (!enabled) return false;
    long now = nanos.getAsLong();
    purge(now);
    if (!id(runId) || !id(projectId) || !id(sessionId) || runs.containsKey(runId)) return false;
    while (runs.size() >= maxRuns) evict(runs.keySet().iterator().next());
    runs.put(runId, new Entry(runId, projectId, sessionId, wall.instant(), now));
    return true;
  }

  /** 実行時間の把握において、削除に該当するか判定する。 */
  private boolean drop(Entry entry) {
    drops++;
    if (entry != null) entry.missing = true;
    return false;
  }

  /** 実行時間の把握において、開始計測区間に該当するか判定する。 */
  @Override
  public synchronized boolean beginSpan(
      String runId,
      String spanId,
      String parentId,
      String requestId,
      String attemptId,
      Category category) {
    if (!enabled) return false;
    long now = nanos.getAsLong();
    purge(now);
    var entry = runs.get(runId);
    if (entry == null) return false;
    if (!id(spanId)
        || spanId.equals(runId)
        || !optionalId(parentId)
        || !optionalId(requestId)
        || !optionalId(attemptId)
        || category == null) return drop(entry);
    if (entry.children.containsKey(spanId)) return false;
    String parent = parentId == null ? runId : parentId;
    if (!parent.equals(runId) && !entry.children.containsKey(parent)) return drop(entry);
    if (entry.end != null || entry.children.size() >= maxPerRun) return drop(entry);
    while (spanCount >= maxTotal) {
      String victim =
          runs.keySet().stream().filter(key -> !key.equals(runId)).findFirst().orElse(null);
      if (victim == null) return drop(entry);
      evict(victim);
    }
    entry.children.put(
        spanId,
        new Child(
            spanId,
            parent,
            requestId,
            attemptId,
            category,
            wall.instant(),
            Math.max(0, now - entry.start)));
    spanCount++;
    return true;
  }

  /** 実行時間の把握において、終了計測区間に該当するか判定する。 */
  @Override
  public synchronized boolean endSpan(String runId, String spanId, Status status) {
    if (!enabled) return false;
    long now = nanos.getAsLong();
    purge(now);
    var entry = runs.get(runId);
    var child = entry == null ? null : entry.children.get(spanId);
    if (child == null || child.end != null || status == null || status == Status.INCOMPLETE)
      return false;
    child.end = Math.max(child.start, now - entry.start);
    child.status = status;
    return true;
  }

  /** 実行時間の把握において、完了実行に該当するか判定する。 */
  @Override
  public synchronized boolean finishRun(String runId, Status status) {
    if (!enabled) return false;
    long now = nanos.getAsLong();
    purge(now);
    var entry = runs.get(runId);
    if (entry == null || entry.end != null || status == null || status == Status.INCOMPLETE)
      return false;
    entry.end = Math.max(0, now - entry.start);
    entry.status = status;
    return true;
  }

  /** 後続の判断に現在の状態を反映するため、計測区間の状態を記録する。 */
  @Override
  public synchronized void markSpan(String runId, String spanId, Metric metric) {
    if (!enabled || metric == null) return;
    long now = nanos.getAsLong();
    purge(now);
    var entry = runs.get(runId);
    var child = entry == null ? null : entry.children.get(spanId);
    if (child != null && child.end == null)
      child.milestones.putIfAbsent(metric, Math.max(0, now - entry.start - child.start));
  }

  /** 後続の判断に現在の状態を反映するため、実行の状態を記録する。 */
  @Override
  public synchronized void markRun(String runId, Metric metric) {
    if (!enabled || metric == null) return;
    long now = nanos.getAsLong();
    purge(now);
    var entry = runs.get(runId);
    if (entry != null && entry.end == null)
      entry.milestones.putIfAbsent(metric, Math.max(0, now - entry.start));
  }

  /** 実行時間の把握で用いる有効を返す。 */
  private static Long valid(Long value) {
    return value != null && value >= 0 ? value : null;
  }

  /** 実行時間の把握に関する使用状況を処理する。 */
  @Override
  public synchronized void usage(
      String runId, String spanId, Long input, Long output, Long generated, Long generation) {
    if (!enabled) return;
    purge(nanos.getAsLong());
    var entry = runs.get(runId);
    var child = entry == null ? null : entry.children.get(spanId);
    if (child == null) return;
    child.input = valid(input);
    child.output = valid(output);
    child.generated = valid(generated);
    child.generation = valid(generation);
    if (child.generated != null && child.output != null && child.generated > child.output) {
      child.generated = null;
      entry.missing = true;
    }
  }

  /** 実行時間の把握に関するプロンプトを処理する。 */
  @Override
  public synchronized void prompt(
      String runId,
      String spanId,
      String feature,
      org.springframework.ai.chat.prompt.Prompt prompt) {
    if (!enabled) return;
    purge(nanos.getAsLong());
    var entry = runs.get(runId);
    var child = entry == null ? null : entry.children.get(spanId);
    if (child == null || child.end != null) return;
    child.llm =
        new LlmObservation(
            "UNKNOWN",
            "UNKNOWN",
            safeLabel(feature),
            null,
            entry.prefix.observe(PrefixDiagnostics.sources(prompt)));
  }

  /** 実行時間の把握で用いる安全性表示名を返す。 */
  private static String safeLabel(String label) {
    return label != null && label.matches("[A-Za-z0-9._:/-]{1,128}") ? label : "UNKNOWN";
  }

  /** 実行時間の把握に関するキャッシュ使用状況を処理する。 */
  @Override
  public synchronized void cacheUsage(
      String runId, String spanId, String provider, String model, Long cached) {
    if (!enabled) return;
    purge(nanos.getAsLong());
    var entry = runs.get(runId);
    var child = entry == null ? null : entry.children.get(spanId);
    if (child == null) return;
    var old = child.llm;
    child.llm =
        new LlmObservation(
            safeLabel(provider),
            safeLabel(model),
            old == null ? "UNKNOWN" : old.feature(),
            new PromptCacheMetrics.Usage(child.input, cached).cached(),
            old == null ? null : old.prefix());
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  @Override
  public synchronized Optional<Run> snapshot(String projectId, String sessionId, String runId) {
    if (!enabled) return Optional.empty();
    long now = nanos.getAsLong();
    purge(now);
    var entry = runs.get(runId);
    return entry != null
            && Objects.equals(entry.project, projectId)
            && Objects.equals(entry.session, sessionId)
        ? Optional.of(snapshot(entry, now))
        : Optional.empty();
  }

  /** 実行時間の把握で用いる最新を返す。 */
  @Override
  public synchronized Optional<Run> latest(String projectId, String sessionId) {
    if (!enabled) return Optional.empty();
    long now = nanos.getAsLong();
    purge(now);
    Entry latest = null;
    for (var entry : runs.values())
      if (Objects.equals(entry.project, projectId) && Objects.equals(entry.session, sessionId))
        latest = entry;
    return latest == null ? Optional.empty() : Optional.of(snapshot(latest, now));
  }

  /** 実行時間の把握で用いる統計を返す。 */
  @Override
  public synchronized Statistics statistics() {
    if (enabled) purge(nanos.getAsLong());
    return new Statistics(runs.size(), spanCount, evictions, drops);
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  private Run snapshot(Entry entry, long now) {
    long elapsed = entry.end == null ? Math.max(0, now - entry.start) : entry.end;
    var spans = entry.children.values().stream().map(Child::snapshot).toList();
    boolean incomplete =
        entry.missing
            || entry.end == null
            || spans.stream().anyMatch(span -> span.endNanos() == null);
    return new Run(
        entry.id,
        entry.project,
        entry.session,
        entry.status,
        entry.wall,
        elapsed,
        incomplete,
        spans,
        entry.milestones,
        aggregate(spans, elapsed));
  }

  /**
   * 実行時間の把握で受け渡す境界を表す。
   *
   * @param at 日時
   * @param category 分類
   * @param delta 差分
   */
  private record Boundary(long at, Category category, int delta) {}

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  private static Summary aggregate(List<Span> spans, long elapsed) {
    var boundaries = new ArrayList<Boundary>();
    long work = 0;
    for (var span : spans) {
      long start = Math.min(elapsed, Math.max(0, span.startNanos()));
      long end =
          Math.min(elapsed, span.endNanos() == null ? elapsed : Math.max(start, span.endNanos()));
      if (end > start) {
        boundaries.add(new Boundary(start, span.category(), 1));
        boundaries.add(new Boundary(end, span.category(), -1));
        work += end - start;
      }
    }
    boundaries.sort(Comparator.comparingLong(Boundary::at));
    var occupancy = new EnumMap<Category, Long>(Category.class);
    for (var category : Category.values()) occupancy.put(category, 0L);
    int[] counts = new int[Category.values().length];
    int active = 0;
    long previous = 0, occupied = 0, overlap = 0, cross = 0;
    int cursor = 0;
    // Group boundaries at the same instant: zero-width transitions create no elapsed time.
    while (cursor < boundaries.size()) {
      long at = boundaries.get(cursor).at(), width = at - previous;
      int categories = 0;
      for (var category : Category.values())
        if (counts[category.ordinal()] > 0) {
          categories++;
          occupancy.merge(category, width, Long::sum);
        }
      if (active > 0) occupied += width;
      if (active >= 2) overlap += width;
      if (categories >= 2) cross += width;
      while (cursor < boundaries.size() && boundaries.get(cursor).at() == at) {
        var boundary = boundaries.get(cursor++);
        active += boundary.delta();
        counts[boundary.category().ordinal()] += boundary.delta();
      }
      previous = at;
    }
    return new Summary(work, occupied, occupancy, overlap, cross, Math.max(0, elapsed - occupied));
  }
}
