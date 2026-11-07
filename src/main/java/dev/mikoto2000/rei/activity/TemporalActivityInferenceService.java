package dev.mikoto2000.rei.activity;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 時系列作業状況推定のユースケースを実現する。
 *
 * <p>Runs on its own single worker. A supplement failure cannot stop observation or Vision.
 */
public final class TemporalActivityInferenceService {
  /**
   * 作業状況の把握で受け渡す最終結果を表す。
   *
   * @param activity 作業状況
   * @param confidence 確信度
   * @param totalTokens 使用トークン数の合計
   */
  public record Outcome(String activity, double confidence, long totalTokens) {}

  /** 作業状況の把握に必要なモデルを扱う。 */
  @FunctionalInterface
  public interface Model {
    /** 作業状況の把握で用いる推定を返す。 */
    Outcome infer(String input, int maxOutputTokens, int timeoutSeconds, long maxTotalTokens)
        throws IOException;
  }

  /** 作業状況の把握に必要な予算失敗情報を扱う。 */
  public static final class BudgetFailure extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = -7603892732009851504L;

    /** トークン数。 */
    final long tokens;

    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public BudgetFailure() {
      this(0);
    }

    /** 指定された値や利用先を使って、BudgetFailureを初期化する。 */
    public BudgetFailure(long tokens) {
      super("Temporal token accounting unavailable or exhausted");
      this.tokens = tokens;
    }
  }

  /** 作業状況の把握に必要なモデル失敗情報を扱う。 */
  public static final class ModelFailure extends IllegalArgumentException {
    @java.io.Serial private static final long serialVersionUID = 896302713937212619L;

    /** トークン数。 */
    final long tokens;

    /** 再試行可能な。 */
    final boolean retryable;

    /** 指定された値や利用先を使って、ModelFailureを初期化する。 */
    public ModelFailure(long tokens, boolean retryable, Throwable cause) {
      super("Temporal completion rejected", cause);
      this.tokens = tokens;
      this.retryable = retryable;
    }
  }

  /**
   * 作業状況の把握で受け渡す計測値を表す。
   *
   * @param completed 完了
   * @param failed 失敗
   * @param skipped 対象外
   * @param llmCalls 言語モデル呼び出し回数
   * @param totalTokens 使用トークン数の合計
   * @param inputChars 入力文字数
   */
  public record Metrics(
      long completed,
      long failed,
      long skipped,
      long llmCalls,
      long totalTokens,
      long inputChars) {}

  /** この機能の動作設定。 */
  private record InferenceLimits(
      int intervalSeconds,
      int windowSeconds,
      int maxRecords,
      int maxRetries,
      int maxInputChars,
      int maxTotalTokens,
      int maxOutputTokens,
      int timeoutSeconds) {}

  private final java.util.function.BooleanSupplier inferenceEnabled;
  private final java.util.function.BooleanSupplier llmEnabled;
  private final java.util.function.Supplier<InferenceLimits> inferenceLimits;

  /** 記録一覧。 */
  @FunctionalInterface
  private interface RecentObservations {
    java.util.List<ActivityRecord> find(Instant start, Instant end, int maximum);
  }

  private final RecentObservations recentObservations;

  /** 保存先。 */
  private final WorkActivityInferenceStore store;

  /** モデル。 */
  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy value) {
    life = value;
  }

  private final Model model;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 実行一覧。 */
  private final Supplier<List<WorkActivityInference.Execution>> executions;

  /** 一時停止中。 */
  private final BooleanSupplier paused;

  /** 直近試行。 */
  private Instant lastAttempt;

  /** 要求内容の照合値。 */
  private String fingerprint;

  /** 候補。 */
  private WorkActivityInference candidate;

  /** 試行回数。 */
  private int attempts;

  /** トークン数。 */
  private long tokens;

  /** 候補根拠。 */
  private TemporalActivityEvidence candidateEvidence;

  /** 候補呼び出し回数。 */
  private int candidateCalls;

  /** 保存待機中。 */
  private boolean savePending;

  /** 初期化済み。 */
  private boolean initialized;

  /** 完了／失敗／対象外／言語モデル呼び出し回数／使用トークン数の合計／入力文字数。 */
  private long completed, failed, skipped, llmCalls, totalTokens, inputChars;

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(TemporalActivityInferenceService.class);

  /** 指定された値や利用先を使って、TemporalActivityInferenceServiceを初期化する。 */
  public TemporalActivityInferenceService(
      ActivityProperties p,
      ActivityStore records,
      WorkActivityInferenceStore store,
      Model model,
      Clock clock,
      Supplier<List<WorkActivityInference.Execution>> executions,
      BooleanSupplier paused) {
    this.inferenceEnabled = () -> p.isEnabled() && p.getTemporal().isEnabled();
    this.llmEnabled = () -> p.getTemporal().isLlmEnabled();
    this.inferenceLimits =
        () -> {
          var temporal = p.getTemporal();
          return new InferenceLimits(
              temporal.getIntervalSeconds(),
              temporal.getWindowSeconds(),
              temporal.getMaxRecords(),
              temporal.getMaxRetries(),
              temporal.getMaxInputChars(),
              temporal.getMaxTotalTokens(),
              temporal.getMaxOutputTokens(),
              temporal.getTimeoutSeconds());
        };
    this.recentObservations =
        (start, end, maximum) -> records.findObservationsBetweenBounded(start, end, maximum);
    this.store = store;
    this.model = model;
    this.clock = clock;
    this.executions = executions;
    this.paused = paused;
  }

  /** 作業状況の把握で用いる計測値を返す。 */
  public synchronized Metrics metrics() {
    return new Metrics(completed, failed, skipped, llmCalls, totalTokens, inputChars);
  }

  /** 作業状況の把握に関する定期確認を処理する。 */
  public synchronized void tick() {
    if (!inferenceEnabled.getAsBoolean() || paused.getAsBoolean()) return;
    var now = clock.instant();
    var lifeLease = life == null ? null : life.begin();
    var settings = inferenceLimits.get();
    if (lastAttempt != null
        && !now.isBefore(lastAttempt)
        && now.isBefore(lastAttempt.plusSeconds(settings.intervalSeconds()))) return;
    lastAttempt = now;
    try {
      var input =
          TemporalActivityEvidence.build(
              recentObservations.find(
                  now.minusSeconds(settings.windowSeconds()),
                  now.plusMillis(1),
                  settings.maxRecords()),
              executions.get(),
              now.minusSeconds(settings.windowSeconds()),
              now);
      if (input.observationIds().isEmpty()) {
        fingerprint = null;
        candidate = null;
        candidateEvidence = null;
        savePending = false;
        skipped++;
        return;
      }
      if (candidateEvidence != null
          && !input.fingerprint().equals(candidateEvidence.fingerprint())) {
        candidate = null;
        candidateEvidence = null;
        savePending = false;
      }
      if (!initialized) {
        initialized = true;
        var latest = store.latest();
        if (latest.isPresent()
            && !latest.get().windowEnd().isAfter(now)
            && !latest.get().windowEnd().isBefore(now.minusSeconds(settings.windowSeconds())))
          fingerprint = latest.get().inputHash();
      }
      if (!input.fingerprint().equals(fingerprint)) {
        if (candidateEvidence == null
            || !input.fingerprint().equals(candidateEvidence.fingerprint())) {
          candidate = input.ruleInference();
          candidateEvidence = input;
          attempts = 0;
          candidateCalls = 0;
          tokens = 0;
          savePending = false;
        }
      } else if (!savePending
          && (candidate == null
              || attempts > settings.maxRetries()
              || !candidate.method().equals("LLM_FAILED_RULES"))) {
        skipped++;
        return;
      }
      var result = candidate;
      boolean retry = false;
      if (!savePending
          && llmEnabled.getAsBoolean()
          && model != null
          && candidate.confidence() > 0
          && input.groups().stream().anyMatch(g -> !g.facts().content().isBlank())) {
        try {
          if (paused.getAsBoolean() || life != null && !life.valid(lifeLease)) return;
          String structured = candidateEvidence.structuredInput(settings.maxInputChars());
          inputChars += structured.length();
          if (tokens >= settings.maxTotalTokens()) throw new BudgetFailure();
          lastAttempt = clock.instant();
          attempts++;
          candidateCalls++;
          llmCalls++;
          var output =
              model.infer(
                  structured,
                  settings.maxOutputTokens(),
                  settings.timeoutSeconds(),
                  settings.maxTotalTokens() - tokens);
          if (output == null || output.totalTokens() <= 0) throw new BudgetFailure();
          if (output.totalTokens() > settings.maxTotalTokens() - tokens)
            throw new BudgetFailure(output.totalTokens());
          tokens += output.totalTokens();
          totalTokens += output.totalTokens();
          if (output.activity() == null
              || output.activity().isBlank()
              || output.activity().length() > 240
              || !Double.isFinite(output.confidence())
              || output.confidence() < 0
              || output.confidence() > .8
              || output.activity().matches("(?s).*(ユーザー[がは]|成功した|完了した|集中して).*"))
            throw new IllegalArgumentException("Unsupported temporal inference");
          result =
              copy(
                  candidate,
                  "画面・れいの記録からの推定: " + TemporalActivityEvidence.clean(output.activity()),
                  Math.min(.7, output.confidence()),
                  "LLM",
                  candidateCalls,
                  tokens);
        } catch (IOException | RuntimeException e) {
          long consumed =
              e instanceof BudgetFailure b ? b.tokens : e instanceof ModelFailure m ? m.tokens : 0;
          tokens += consumed;
          totalTokens += consumed;
          if (e instanceof BudgetFailure
              || (e instanceof ModelFailure m
                  ? !m.retryable
                  : e instanceof IllegalArgumentException)) attempts = settings.maxRetries() + 1;
          retry = true;
          failed++;
          result =
              copy(
                  candidate,
                  candidate.inferredActivity(),
                  candidate.confidence(),
                  "LLM_FAILED_RULES",
                  candidateCalls,
                  tokens);
          log.warn(
              "Activity temporal model failed: reason={} base_records_retained=true",
              e.getClass().getSimpleName());
        }
      }
      candidate = result;
      if (!retry && !savePending) attempts = settings.maxRetries() + 1;
      savePending = true;
      if (paused.getAsBoolean() || life != null && !life.valid(lifeLease)) return;
      store.save(result);
      savePending = false;
      fingerprint = input.fingerprint();
      completed++;
    } catch (RuntimeException e) {
      failed++;
      log.warn(
          "Activity temporal inference failed: reason={} base_records_retained=true",
          e.getClass().getSimpleName());
    } finally {
      var m = metrics();
      log.info(
          "Activity temporal metrics: completed={} failed={} skipped={} llm_calls={} total_tokens={} input_chars={}",
          m.completed(),
          m.failed(),
          m.skipped(),
          m.llmCalls(),
          m.totalTokens(),
          m.inputChars());
    }
  }

  /** 作業状況の把握で用いる複製を返す。 */
  private WorkActivityInference copy(
      WorkActivityInference r,
      String activity,
      double confidence,
      String method,
      int calls,
      long tokens) {
    return new WorkActivityInference(
        r.id(),
        r.inputHash(),
        clock.instant(),
        r.windowStart(),
        r.windowEnd(),
        r.projectId(),
        r.project(),
        activity,
        r.observationIds(),
        r.executions(),
        confidence,
        confidence == 0 ? "UNKNOWN" : r.status(),
        method,
        calls,
        tokens);
  }
}
