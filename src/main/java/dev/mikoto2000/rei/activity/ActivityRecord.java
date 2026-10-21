package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/**
 * 作業状況の観測事実と推定結果を記録する。画面画像の参照先は記録本体と別に期限切れになり得る。
 *
 * <p>Long-lived evidence and explicitly uncertain inference; screenshot references may expire
 * independently.
 *
 * @param id 識別子
 * @param capturedAt 観測日時
 * @param durationEstimate 推定継続時間
 * @param observations 観測内容一覧
 * @param foreground 前面
 * @param inference 推定
 * @param confidence 確信度
 * @param screenshotReferences 保存した画面画像の参照先
 * @param changeAmount 画面の変化量
 * @param duplicate 重複の有無
 * @param continuityId 作業の継続を識別する値
 * @param detection 検出情報
 */
public record ActivityRecord(
    String id,
    Instant capturedAt,
    long durationEstimate,
    List<Observation> observations,
    ForegroundWindow foreground,
    Inference inference,
    double confidence,
    List<String> screenshotReferences,
    double changeAmount,
    boolean duplicate,
    String continuityId,
    Detection detection) {
  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityRecord(
      String id,
      Instant capturedAt,
      long durationEstimate,
      List<Observation> observations,
      ForegroundWindow foreground,
      Inference inference,
      double confidence,
      List<String> screenshotReferences,
      double changeAmount,
      boolean duplicate,
      String continuityId) {
    this(
        id,
        capturedAt,
        durationEstimate,
        observations,
        foreground,
        inference,
        confidence,
        screenshotReferences,
        changeAmount,
        duplicate,
        continuityId,
        null);
  }

  /**
   * 作業状況の把握で受け渡す検出情報を表す。
   *
   * @param evidence 根拠
   * @param classificationSources 分類に用いた情報源
   * @param visionUsed 画像認識を利用したかどうか
   * @param classificationMode 分類方法
   * @param status 状態
   * @param sourceConfidence 取得元ごとの確信度
   * @param reason 理由
   * @param fieldConfidence 各項目の確信度
   * @param secondaryConfidence 補助分類ごとの確信度
   * @param diagnostics 診断情報
   * @param visionDiagnostics 画像認識の診断情報
   */
  public record Detection(
      ActivityEvidence evidence,
      List<String> classificationSources,
      boolean visionUsed,
      String classificationMode,
      String status,
      java.util.Map<String, Double> sourceConfidence,
      String reason,
      ActivityFieldConfidence fieldConfidence,
      List<ActivityClassification.Secondary> secondaryConfidence,
      ClassificationDiagnostics diagnostics,
      VisionDiagnostics visionDiagnostics) {
    /** 受け取った情報からDetectionを作成する。 */
    public Detection {
      classificationSources = List.copyOf(classificationSources);
      sourceConfidence = java.util.Map.copyOf(sourceConfidence);
      secondaryConfidence =
          secondaryConfidence == null ? List.of() : List.copyOf(secondaryConfidence);
    }

    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Detection(
        ActivityEvidence evidence,
        List<String> sources,
        boolean visionUsed,
        String mode,
        String status,
        java.util.Map<String, Double> weights,
        String reason,
        ActivityFieldConfidence fields,
        List<ActivityClassification.Secondary> secondary,
        ClassificationDiagnostics diagnostics) {
      this(
          evidence,
          sources,
          visionUsed,
          mode,
          status,
          weights,
          reason,
          fields,
          secondary,
          diagnostics,
          null);
    }

    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Detection(
        ActivityEvidence evidence,
        List<String> sources,
        boolean visionUsed,
        String mode,
        String status,
        java.util.Map<String, Double> weights,
        String reason) {
      this(evidence, sources, visionUsed, mode, status, weights, reason, null, List.of(), null);
    }

    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Detection(
        ActivityEvidence evidence,
        List<String> sources,
        boolean visionUsed,
        String mode,
        String status,
        java.util.Map<String, Double> weights,
        String reason,
        ActivityFieldConfidence fields,
        List<ActivityClassification.Secondary> secondary) {
      this(evidence, sources, visionUsed, mode, status, weights, reason, fields, secondary, null);
    }
  }

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityRecord(
      String id,
      Instant capturedAt,
      long durationEstimate,
      List<Observation> observations,
      ForegroundWindow foreground,
      Inference inference,
      double confidence,
      List<String> screenshotReferences,
      double changeAmount,
      boolean duplicate) {
    this(
        id,
        capturedAt,
        durationEstimate,
        observations,
        foreground,
        inference,
        confidence,
        screenshotReferences,
        changeAmount,
        duplicate,
        "legacy");
  }

  /** 受け取った情報からActivityRecordを作成する。 */
  public ActivityRecord {
    if (id == null
        || capturedAt == null
        || durationEstimate < 0
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1) throw new IllegalArgumentException("Invalid activity record");
    observations = List.copyOf(observations);
    screenshotReferences = List.copyOf(screenshotReferences);
  }

  /**
   * 作業状況の把握で受け渡す許容範囲を表す。
   *
   * @param x 横方向の位置
   * @param y 縦方向の位置
   * @param width 幅
   * @param height 高さ
   */
  public record Bounds(int x, int y, int width, int height) {}

  /**
   * 作業状況の把握で受け渡す観測内容を表す。
   *
   * @param monitor 排他制御
   * @param bounds 許容範囲
   * @param capturedAt 観測日時
   */
  public record Observation(String monitor, Bounds bounds, Instant capturedAt) {}

  /**
   * 作業状況の把握で受け渡す推定を表す。
   *
   * @param summary 要約
   * @param activities 作業状況の記録
   */
  public record Inference(String summary, List<Activity> activities) {
    /** 受け取った情報からInferenceを作成する。 */
    public Inference {
      activities = List.copyOf(activities);
    }
  }

  /**
   * 作業状況の把握で受け渡す作業状況を表す。
   *
   * <p>Rule/model candidates, including visible application/content, are separate from OS facts.
   *
   * @param monitor 排他制御
   * @param type 種別
   * @param application アプリケーション
   * @param service サービス
   * @param contentTitle 内容タイトル
   * @param projectCandidate プロジェクト候補
   */
  public record Activity(
      String monitor,
      String type,
      String application,
      String service,
      String contentTitle,
      String projectCandidate) {}
}
