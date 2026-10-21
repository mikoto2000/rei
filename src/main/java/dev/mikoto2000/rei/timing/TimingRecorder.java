package dev.mikoto2000.rei.timing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 実行時間の把握に必要な時間計測Recorderを扱う。
 *
 * <p>Metadata-only local timing; optional metrics never use text length as token counts.
 */
public interface TimingRecorder {
  /** 実行時間の把握における分類の選択肢。 */
  enum Category {
    /** 言語モデル。 */
    LLM,
    /** ツール。 */
    TOOL,
    /** 検索。 */
    SEARCH,
    /** 承認待機。 */
    APPROVAL_WAIT,
    /** 再試行。 */
    RETRY,
    /** 完了条件検証。 */
    COMPLETION_VALIDATION,
    /** その他。 */
    OTHER
  }

  /** 実行時間の把握における状態の選択肢。 */
  enum Status {
    /** 成功。 */
    SUCCESS,
    /** 失敗。 */
    FAILED,
    /** キャンセル済み。 */
    CANCELLED,
    /** TIMED出力先。 */
    TIMED_OUT,
    /** 切断済み。 */
    DISCONNECTED,
    /** 未完了。 */
    INCOMPLETE
  }

  /** 実行時間の把握におけるMetricの選択肢。 */
  enum Metric {
    /** 最初COMMUNICATION。 */
    FIRST_COMMUNICATION,
    /** 最初FRAMEWORK文書の分割単位。 */
    FIRST_FRAMEWORK_CHUNK,
    /** 最初生成済みトークン。 */
    FIRST_GENERATED_TOKEN,
    /** 最初生成テキスト。 */
    FIRST_GENERATION_TEXT,
    /** 最初表示対象出力。 */
    FIRST_VISIBLE_OUTPUT
  }

  /**
   * 実行時間の把握で受け渡す計測区間を表す。
   *
   * @param id 識別子
   * @param parentId 親の識別子
   * @param requestId 要求の識別子
   * @param attemptId 試行識別子
   * @param category 分類
   * @param status 状態
   * @param startedAt 開始日時
   * @param startNanos 開始ナノ秒数
   * @param endNanos 終了ナノ秒数
   * @param milestones 達成の節目
   * @param inputTokens 入力トークン数
   * @param outputTokens 出力トークン数
   * @param generatedTokens 生成済みトークン数
   * @param generationNanos 生成ナノ秒数
   * @param llm 言語モデル
   */
  record Span(
      String id,
      String parentId,
      String requestId,
      String attemptId,
      Category category,
      Status status,
      Instant startedAt,
      long startNanos,
      Long endNanos,
      Map<Metric, Long> milestones,
      Long inputTokens,
      Long outputTokens,
      Long generatedTokens,
      Long generationNanos,
      LlmObservation llm) {
    /** 受け取った情報からSpanを作成する。 */
    public Span {
      milestones = Map.copyOf(milestones);
    }

    /** 実行時間の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Span(
        String id,
        String parentId,
        String requestId,
        String attemptId,
        Category category,
        Status status,
        Instant startedAt,
        long startNanos,
        Long endNanos,
        Map<Metric, Long> milestones,
        Long inputTokens,
        Long outputTokens,
        Long generatedTokens,
        Long generationNanos) {
      this(
          id,
          parentId,
          requestId,
          attemptId,
          category,
          status,
          startedAt,
          startNanos,
          endNanos,
          milestones,
          inputTokens,
          outputTokens,
          generatedTokens,
          generationNanos,
          null);
    }

    /**
     * 実行時間の把握で用いる生成Tpsを返す。
     *
     * <p>Only an explicit real generated count and matching measured/provider duration qualify.
     */
    public Double generationTps() {
      return generatedTokens == null || generationNanos == null || generationNanos <= 0
          ? null
          : generatedTokens / (generationNanos / 1_000_000_000d);
    }
  }

  /**
   * 実行時間の把握で受け渡す言語モデル観測内容を表す。
   *
   * @param provider 提供元
   * @param model モデル
   * @param feature 機能
   * @param cachedInputTokens 保存済み入力トークン数
   * @param prefix 接頭辞
   */
  record LlmObservation(
      String provider,
      String model,
      String feature,
      Long cachedInputTokens,
      PrefixDiagnostics.Snapshot prefix) {}

  /** 実行時間の把握に関するプロンプトを処理する。 */
  default void prompt(
      String runId,
      String spanId,
      String feature,
      org.springframework.ai.chat.prompt.Prompt prompt) {}

  /** 実行時間の把握に関するキャッシュ使用状況を処理する。 */
  default void cacheUsage(
      String runId, String spanId, String provider, String model, Long cachedInputTokens) {}

  /**
   * 実行時間の把握で受け渡す要約を表す。
   *
   * @param workNanos 作業ナノ秒数
   * @param occupiedNanos occupiedナノ秒数
   * @param occupancyNanos occupancyナノ秒数
   * @param overlapNanos overlapナノ秒数
   * @param crossCategoryOverlapNanos cross分類Overlapナノ秒数
   * @param unmeasuredNanos unmeasuredナノ秒数
   */
  record Summary(
      long workNanos,
      long occupiedNanos,
      Map<Category, Long> occupancyNanos,
      long overlapNanos,
      long crossCategoryOverlapNanos,
      long unmeasuredNanos) {
    /** 受け取った情報からSummaryを作成する。 */
    public Summary {
      occupancyNanos = Map.copyOf(occupancyNanos);
    }
  }

  /**
   * 実行時間の把握で受け渡す実行を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param status 状態
   * @param startedAt 開始日時
   * @param elapsedNanos 経過時間ナノ秒数
   * @param incomplete 未完了
   * @param spans 対象区間
   * @param milestones 達成の節目
   * @param summary 要約
   */
  record Run(
      String id,
      String projectId,
      String sessionId,
      Status status,
      Instant startedAt,
      long elapsedNanos,
      boolean incomplete,
      List<Span> spans,
      Map<Metric, Long> milestones,
      Summary summary) {
    /** 受け取った情報からRunを作成する。 */
    public Run {
      spans = List.copyOf(spans);
      milestones = Map.copyOf(milestones);
    }
  }

  /**
   * 実行時間の把握で受け渡す統計を表す。
   *
   * @param runs 実行一覧
   * @param spans 対象区間
   * @param evictedRuns evicted実行一覧
   * @param droppedSpans 除外された対象区間
   */
  record Statistics(int runs, int spans, long evictedRuns, long droppedSpans) {}

  /** この機能を利用する設定が有効かどうかを返す。 */
  boolean enabled();

  /** 実行時間の把握において、開始実行に該当するか判定する。 */
  boolean beginRun(String runId, String projectId, String sessionId);

  /** 実行時間の把握において、開始計測区間に該当するか判定する。 */
  boolean beginSpan(
      String runId,
      String spanId,
      String parentId,
      String requestId,
      String attemptId,
      Category category);

  /** 実行時間の把握において、終了計測区間に該当するか判定する。 */
  boolean endSpan(String runId, String spanId, Status status);

  /** 実行時間の把握において、完了実行に該当するか判定する。 */
  boolean finishRun(String runId, Status status);

  /** 後続の判断に現在の状態を反映するため、計測区間の状態を記録する。 */
  void markSpan(String runId, String spanId, Metric metric);

  /** 後続の判断に現在の状態を反映するため、実行の状態を記録する。 */
  void markRun(String runId, Metric metric);

  /** 実行時間の把握に関する使用状況を処理する。 */
  void usage(
      String runId,
      String spanId,
      Long inputTokens,
      Long outputTokens,
      Long generatedTokens,
      Long generationNanos);

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  Optional<Run> snapshot(String projectId, String sessionId, String runId);

  /** 実行時間の把握で用いる最新を返す。 */
  Optional<Run> latest(String projectId, String sessionId);

  /** 実行時間の把握で用いる統計を返す。 */
  Statistics statistics();
}
