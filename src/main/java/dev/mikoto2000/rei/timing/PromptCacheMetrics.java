package dev.mikoto2000.rei.timing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Provider-reported cache usage, separate from structural prefix diagnostics. null means UNKNOWN.
 */
public final class PromptCacheMetrics {
  private PromptCacheMetrics() {}

  public record Usage(Long input, Long cached) {
    public Usage {
      if (input != null && input < 0) input = null;
      if (cached != null && (cached < 0 || input != null && cached > input)) cached = null;
    }

    public Long uncached() {
      return input == null || cached == null ? null : input - cached;
    }

    public Double hitRate() {
      return input == null || input == 0 || cached == null ? null : (double) cached / input;
    }

    public boolean usageSupported() {
      return input != null;
    }

    public boolean cacheMetricsSupported() {
      return cached != null;
    }
  }

  public static Usage usage(org.springframework.ai.chat.metadata.Usage usage) {
    if (usage == null || usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)
      return new Usage(null, null);
    return new Usage(
        usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue(),
        usage.getCacheReadInputTokens());
  }

  public record Call(
      String provider,
      String model,
      String feature,
      Usage usage,
      Long ttftNanos,
      Long durationNanos) {}

  public record Distribution(int count, Long min, Double mean, Double median, Long max) {}

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

  public static Map<String, Aggregate> byModel(List<Call> calls) {
    return group(calls, c -> c.provider() + "/" + c.model());
  }

  public static Map<String, Aggregate> byFeature(List<Call> calls) {
    return group(calls, Call::feature);
  }

  private static Map<String, Aggregate> group(
      List<Call> calls, java.util.function.Function<Call, String> key) {
    var grouped = new TreeMap<String, List<Call>>();
    for (var call : calls)
      grouped.computeIfAbsent(key.apply(call), ignored -> new ArrayList<>()).add(call);
    var result = new TreeMap<String, Aggregate>();
    grouped.forEach((id, values) -> result.put(id, aggregate(values)));
    return Collections.unmodifiableMap(result);
  }

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
