package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握で受け渡す画像認識の診断情報を表す。
 *
 * <p>Optional per-scope outcome. Legacy visionUsed means attempted, not accepted evidence.
 *
 * @param foreground 前面
 * @param background バックグラウンド
 */
public record VisionDiagnostics(Result foreground, Result background) {
  /** 作業状況の把握における状態の選択肢。 */
  public enum State {
    /** 否定ATTEMPTED。 */
    NOT_ATTEMPTED,
    /** 実行を試みた状態。 */
    ATTEMPTED,
    /** ATTEMPTED失敗。 */
    ATTEMPTED_FAILED,
    /** ATTEMPTED成功否定使用量。 */
    ATTEMPTED_SUCCEEDED_NOT_USED,
    /** 使用量。 */
    USED,
    /** 不明。 */
    UNKNOWN
  }

  /**
   * 作業状況の把握で受け渡す時間計測を表す。
   *
   * @param observationId 観測内容識別子
   * @param imageCapturedAt 画像観測日時
   * @param startedAt 開始日時
   * @param completedAt 完了日時
   */
  public record Timing(
      String observationId,
      java.time.Instant imageCapturedAt,
      java.time.Instant startedAt,
      java.time.Instant completedAt) {}

  /**
   * 作業状況の把握で受け渡す文脈を表す。
   *
   * @param observationId 観測内容識別子
   * @param observedAt 観測日時
   * @param candidates 候補一覧
   * @param confidence 確信度
   */
  public record Context(
      String observationId,
      java.time.Instant observedAt,
      java.util.List<ActivityRecord.Activity> candidates,
      double confidence) {
    /** 受け取った情報からContextを作成する。 */
    public Context {
      candidates = java.util.List.copyOf(candidates);
      if (candidates.size() > 3 || !Double.isFinite(confidence) || confidence < 0 || confidence > 1)
        throw new IllegalArgumentException("Invalid desktop context");
    }
  }

  /**
   * 作業状況の把握で受け渡す結果を表す。
   *
   * @param state 状態
   * @param failure 失敗情報
   * @param timing 時間計測
   * @param context 文脈
   */
  public record Result(State state, ActivityVisionFailure failure, Timing timing, Context context) {
    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Result(State state, ActivityVisionFailure failure, Timing timing) {
      this(state, failure, timing, null);
    }

    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Result(State state, ActivityVisionFailure failure) {
      this(state, failure, null);
    }
  }

  /** 作業状況の把握で用いる初期を返す。 */
  public static VisionDiagnostics initial() {
    return new VisionDiagnostics(
        new Result(State.NOT_ATTEMPTED, null), new Result(State.NOT_ATTEMPTED, null));
  }

  /** 指定されたを反映した情報を作成する。 */
  public VisionDiagnostics with(
      boolean backgroundScope, State state, ActivityVisionFailure failure) {
    var prior = backgroundScope ? background : foreground;
    var result =
        new Result(
            state,
            failure,
            prior == null ? null : prior.timing(),
            prior == null ? null : prior.context());
    return backgroundScope
        ? new VisionDiagnostics(foreground, result)
        : new VisionDiagnostics(result, background);
  }

  /** 作業状況の把握で用いる時間計測を返す。 */
  public VisionDiagnostics timing(boolean backgroundScope, Timing timing) {
    var prior = backgroundScope ? background : foreground;
    var result =
        new Result(
            prior == null ? State.UNKNOWN : prior.state(),
            prior == null ? null : prior.failure(),
            timing,
            prior == null ? null : prior.context());
    return backgroundScope
        ? new VisionDiagnostics(foreground, result)
        : new VisionDiagnostics(result, background);
  }

  /** 作業状況の把握で用いる文脈を返す。 */
  public VisionDiagnostics context(Context context) {
    var prior = background;
    return new VisionDiagnostics(
        foreground,
        new Result(
            prior == null ? State.UNKNOWN : prior.state(),
            prior == null ? null : prior.failure(),
            prior == null ? null : prior.timing(),
            context));
  }

  /** 作業状況の把握で受け渡す情報を、指定された内容から作成する。 */
  public static VisionDiagnostics of(ActivityRecord.Detection d) {
    if (d != null && d.visionDiagnostics() != null) return d.visionDiagnostics();
    // Old sources and visionUsed included failed and rejected results. Never infer USED.
    return new VisionDiagnostics(legacy(d, "VISION_FOREGROUND"), legacy(d, "VISION_BACKGROUND"));
  }

  /** 作業状況の把握で用いる旧形式を返す。 */
  private static Result legacy(ActivityRecord.Detection d, String scope) {
    if (d == null || "LEGACY".equals(d.classificationMode()))
      return new Result(State.UNKNOWN, null);
    if (!d.classificationSources().contains(scope))
      return new Result(d.visionUsed() ? State.UNKNOWN : State.NOT_ATTEMPTED, null);
    return new Result(State.UNKNOWN, null);
  }
}
