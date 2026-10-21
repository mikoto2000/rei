package dev.mikoto2000.rei.timing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 実行時間の把握に必要なプロンプトキャッシュ計測値を扱う。
 *
 * <p>Provider-reported cache usage, separate from structural prefix diagnostics. null means
 * UNKNOWN.
 */
public final class PromptCacheMetrics {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private PromptCacheMetrics() {}

  /**
   * 実行時間の把握で受け渡す使用状況を表す。
   *
   * @param input 入力
   * @param cached 保存済み
   */
  public record Usage(Long input, Long cached) {
    /** 受け取った情報からUsageを作成する。 */
    public Usage {
      if (input != null && input < 0) input = null;
      if (cached != null && (cached < 0 || input != null && cached > input)) cached = null;
    }

    /** 実行時間の把握で用いるキャッシュされていない対象を返す。 */
    public Long uncached() {
      return input == null || cached == null ? null : input - cached;
    }

    /** 実行時間の把握で用いる検索結果頻度を返す。 */
    public Double hitRate() {
      return input == null || input == 0 || cached == null ? null : (double) cached / input;
    }

    /** 実行時間の把握において、使用状況対応済みに該当するか判定する。 */
    public boolean usageSupported() {
      return input != null;
    }

    /** 実行時間の把握において、キャッシュ計測値対応済みに該当するか判定する。 */
    public boolean cacheMetricsSupported() {
      return cached != null;
    }
  }

  /** 実行時間の把握で用いる使用状況を返す。 */
  public static Usage usage(org.springframework.ai.chat.metadata.Usage usage) {
    if (usage == null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)
      return new Usage(null, null);
    return new Usage(
        usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue(),
        usage.getCacheReadInputTokens());
  }

  /**
   * 実行時間の把握で受け渡す呼び出しを表す。
   *
   * @param provider 提供元
   * @param model モデル
   * @param feature 機能
   * @param usage 使用状況
   * @param ttftNanos 最初の応答までの時間ナノ秒数
   * @param durationNanos 継続時間ナノ秒数
   */
  public record Call(
      String provider,
      String model,
      String feature,
      Usage usage,
      Long ttftNanos,
      Long durationNanos) {}

  /**
   * 実行時間の把握で受け渡す分布を表す。
   *
   * @param count 件数
   * @param min 最小
   * @param mean 平均
   * @param median 中央値
   * @param max 最大
   */
  public record Distribution(int count, Long min, Double mean, Double median, Long max) {}

  /**
   * 実行時間の把握で受け渡す集計結果を表す。
   *
   * @param calls 呼び出し回数
   * @param inputMeasuredCalls 入力計測した呼び出し回数
   * @param input 入力
   * @param cacheMeasuredCalls キャッシュ計測した呼び出し回数
   * @param cached 保存済み
   * @param uncached キャッシュされていない対象
   * @param hitRate 検索結果頻度
   * @param ttft 最初の応答までの時間
   * @param duration 継続時間
   */
  public record Aggregate(
      int calls,
      int inputMeasuredCalls,
      Long input,
      int cacheMeasuredCalls,
      Long cached,
      Long uncached,
      Double hitRate,
      Distribution ttft,
      Distribution duration) {}

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  public static Aggregate aggregate(List<Call> calls) {
    var input = calls.stream().filter(c -> c.usage().input() != null).toList();
    var measured = calls.stream().filter(c -> c.usage().uncached() != null).toList();
    long denominator = measured.stream().mapToLong(c -> c.usage().input()).sum();
    long cached = measured.stream().mapToLong(c -> c.usage().cached()).sum();
    return new Aggregate(
        calls.size(),
        input.size(),
        input.isEmpty() ? null : input.stream().mapToLong(c -> c.usage().input()).sum(),
        measured.size(),
        measured.isEmpty() ? null : cached,
        measured.isEmpty() ? null : denominator - cached,
        denominator == 0 ? null : (double) cached / denominator,
        distribution(calls.stream().map(Call::ttftNanos).toList()),
        distribution(calls.stream().map(Call::durationNanos).toList()));
  }

  /** 実行時間の把握で用いる別モデルを返す。 */
  public static Map<String, Aggregate> byModel(List<Call> calls) {
    return group(calls, c -> c.provider() + "/" + c.model());
  }

  /** 実行時間の把握で用いる別機能を返す。 */
  public static Map<String, Aggregate> byFeature(List<Call> calls) {
    return group(calls, Call::feature);
  }

  /** 実行時間の把握で用いるグループを返す。 */
  private static Map<String, Aggregate> group(
      List<Call> calls, java.util.function.Function<Call, String> key) {
    var grouped = new TreeMap<String, List<Call>>();
    for (var call : calls)
      grouped.computeIfAbsent(key.apply(call), ignored -> new ArrayList<>()).add(call);
    var result = new TreeMap<String, Aggregate>();
    grouped.forEach((id, values) -> result.put(id, aggregate(values)));
    return Collections.unmodifiableMap(result);
  }

  /** 実行時間の把握で用いる分布を返す。 */
  private static Distribution distribution(List<Long> raw) {
    var values = raw.stream().filter(Objects::nonNull).sorted().toList();
    int n = values.size();
    if (n == 0) return new Distribution(0, null, null, null, null);
    double median =
        n % 2 == 1 ? values.get(n / 2) : values.get(n / 2 - 1) / 2d + values.get(n / 2) / 2d;
    return new Distribution(
        n,
        values.getFirst(),
        values.stream().mapToDouble(Long::doubleValue).average().orElseThrow(),
        median,
        values.getLast());
  }

  /** 実行時間の把握で用いる呼び出し回数を返す。 */
  public static List<Call> calls(TimingRecorder.Run run) {
    return run.spans().stream()
        .filter(s -> s.category() == TimingRecorder.Category.LLM)
        .map(
            s -> {
              var o = s.llm();
              return new Call(
                  o == null ? "UNKNOWN" : o.provider(),
                  o == null ? "UNKNOWN" : o.model(),
                  o == null ? "UNKNOWN" : o.feature(),
                  new Usage(s.inputTokens(), o == null ? null : o.cachedInputTokens()),
                  s.milestones().get(TimingRecorder.Metric.FIRST_GENERATION_TEXT),
                  s.endNanos() == null ? null : s.endNanos() - s.startNanos());
            })
        .toList();
  }
}
