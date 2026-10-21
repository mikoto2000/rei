package dev.mikoto2000.rei.activity;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 作業状況の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Data
@ConfigurationProperties("rei.activity")
public class ActivityProperties {
  /** 有効状態。 */
  private boolean enabled = false;

  /**
   * 作業文脈有効状態。
   *
   * <p>Explicit opt-in to observation-time Git and saved Work Context references.
   */
  private boolean workContextEnabled = false;

  /** extraction有効状態。 */
  private boolean extractionEnabled = true;

  /** keep画面画像一覧。 */
  private boolean keepScreenshots = false;

  /** keep通知時Extraction失敗情報。 */
  private boolean keepOnExtractionFailure = false;

  /** 取得記録実行間隔秒数。 */
  private int captureIntervalSeconds = 60;

  /** 観測内容。 */
  private Observation observation = new Observation();

  /** 画像認識実行待ち。 */
  private VisionQueue visionQueue = new VisionQueue();

  /** 時系列。 */
  private Temporal temporal = new Temporal();

  /** 作業状況の把握に必要な時系列を扱う。 */
  @Data
  public static class Temporal {
    /** 有効状態。 */
    private boolean enabled = true;

    /** 言語モデル有効状態。 */
    private boolean llmEnabled = false;

    /** 範囲秒数。 */
    private int windowSeconds = 300;

    /** 実行間隔秒数。 */
    private int intervalSeconds = 60;

    /** 最大記録一覧。 */
    private int maxRecords = 120;

    /** 最大入力文字数。 */
    private int maxInputChars = 12000;

    /** 最大出力トークン数。 */
    private int maxOutputTokens = 512;

    /** 最大使用トークン数の合計。 */
    private int maxTotalTokens = 8192;

    /** 最大再試行回数。 */
    private int maxRetries = 1;

    /** タイムアウトの秒数。 */
    private int timeoutSeconds = 20;
  }

  /** 作業状況の把握に必要な画像認識実行待ちを扱う。 */
  @Data
  public static class VisionQueue {
    /** 有効状態。 */
    private boolean enabled = true;

    /** 最小開始実行間隔秒数。 */
    private int minimumStartIntervalSeconds = 30;

    /** 最大再確認実行間隔秒数。 */
    private int maxRefreshIntervalSeconds = 300;
  }

  /** 作業状況の把握に必要な観測内容を扱う。 */
  @Data
  public static class Observation {
    /** 入力Aware有効状態。 */
    private boolean inputAwareEnabled = true;

    /** 実行間隔秒数。 */
    private int intervalSeconds = 15;

    /** 最大観測内容実行間隔秒数。 */
    private int maxObservationIntervalSeconds = 300;
  }

  /** 作業状況の把握で用いる観測内容継続時間秒数を返す。 */
  public int observationDurationSeconds(boolean lightweight) {
    return lightweight
        ? Math.min(captureIntervalSeconds, observation.intervalSeconds)
        : captureIntervalSeconds;
  }

  /** 画像認識画像倍率。 */
  private double visionImageScale = .5;

  /** バックグラウンド分析実行間隔秒数。 */
  private int backgroundAnalysisIntervalSeconds = 300;

  /** 検出情報。 */
  private Detection detection = new Detection();

  /** デスクトップ文脈。 */
  private DesktopContext desktopContext = new DesktopContext();

  /** 作業状況の把握に必要なデスクトップ文脈を扱う。 */
  @Data
  public static class DesktopContext {
    /**
     * 監視の窓口一覧。
     *
     * <p>Empty selects all connected displays. IDs come from the existing Robot capture adapter.
     */
    private List<String> monitors = List.of();

    /** 観測対象から隠す画面領域。 */
    private List<Mask> masks = List.of();
  }

  /**
   * 作業状況の把握で受け渡す非公開化を表す。
   *
   * @param monitor 排他制御
   * @param x 横方向の位置
   * @param y 縦方向の位置
   * @param width 幅
   * @param height 高さ
   */
  public record Mask(String monitor, int x, int y, int width, int height) {}

  /** 分類。 */
  private Classification classification = new Classification();

  /** 要約。 */
  private Summary summary = new Summary();

  /** 助言。 */
  private Coaching coaching = new Coaching();

  /** 作業状況の把握に必要な助言を扱う。 */
  @Data
  public static class Coaching {
    /** 自動有効状態。 */
    private boolean automaticEnabled = false;

    /** 週次有効状態。 */
    private boolean weeklyEnabled = true;

    /** 月次有効状態。 */
    private boolean monthlyEnabled = true;

    /** 確認実行間隔秒数。 */
    private int checkIntervalSeconds = 3600;
  }

  /** 作業状況の把握に必要な要約を扱う。 */
  @Data
  public static class Summary {
    /** 言語モデル有効状態。 */
    private boolean llmEnabled = true;

    /** タイムアウトの秒数。 */
    private int timeoutSeconds = 30;

    /** プロジェクト別名一覧ファイル。 */
    private String projectAliasesFile = "activity/project-aliases.yaml";
  }

  /** 作業状況の把握に必要な分類を扱う。 */
  @Data
  public static class Classification {
    /** 利用者規則一覧ファイル。 */
    private String userRulesFile = "activity/classification-rules.yaml";

    /** hot再読み込み。 */
    private boolean hotReload = true;

    /** 不明登録台帳。 */
    private Registry unknownRegistry = new Registry();

    /** 娯楽登録台帳。 */
    private Registry entertainmentRegistry = new Registry();

    /** 規則提案。 */
    private RuleSuggestion ruleSuggestion = new RuleSuggestion();

    /** 診断情報。 */
    private Diagnostics diagnostics = new Diagnostics();
  }

  /** 作業状況の把握に必要な登録台帳を扱う。 */
  @Data
  public static class Registry {
    /** 有効状態。 */
    private boolean enabled = true;

    /** 保持期間日数。 */
    private int retentionDays = 30;

    /** 最大項目一覧。 */
    private int maxEntries = 1000;
  }

  /** 作業状況の把握に必要な規則提案を扱う。 */
  @Data
  public static class RuleSuggestion {
    /** 既定最大出力トークン数。 */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

    /** 有効状態。 */
    private boolean enabled = true;

    /** 最小サンプル一覧。 */
    private int minimumSamples = 3;

    /** 最大出力トークン数。 */
    private int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
  }

  /** 作業状況の把握に必要な診断情報を扱う。 */
  @Data
  public static class Diagnostics {
    /** 有効状態。 */
    private boolean enabled = true;
  }

  /** 作業状況の把握における検出情報動作モードの選択肢。 */
  public enum DetectionMode {
    /** 根拠最初。 */
    EVIDENCE_FIRST,
    /** 画像認識最初。 */
    VISION_FIRST
  }

  /** 作業状況の把握に必要な検出情報を扱う。 */
  @Data
  public static class Detection {
    /** 動作モード。 */
    private DetectionMode mode = DetectionMode.EVIDENCE_FIRST;

    /** 根拠有効状態。 */
    private boolean evidenceEnabled = true;

    /** 画像認識有効状態。 */
    private boolean visionEnabled = true;

    /** 代替手段有効状態。 */
    private boolean fallbackEnabled = true;

    /** 対象外画像認識確信度。 */
    private double skipVisionConfidence = .8;

    /** 前面表示範囲の調整。 */
    private boolean foregroundCrop = true;

    /** バックグラウンド全体画面有効状態。 */
    private boolean backgroundFullScreenEnabled = false;

    /** 最大出力トークン数。 */
    private int maxOutputTokens = 2048;
  }

  /** 画面画像保持期間日数。 */
  private int screenshotRetentionDays = 3;

  /** 変更判定の境界値。 */
  private double changeThreshold = .03;

  /** 会話間隔秒数。 */
  private int sessionGapSeconds = 90;

  /**
   * 要約間隔秒数。
   *
   * <p>Compatibility cap for explicitly configured Phase 3 settings.
   */
  private Integer summaryGapSeconds;

  /** 要約通常統合間隔秒数。 */
  private int summaryNormalMergeGapSeconds = 120;

  /** 要約最大統合間隔秒数。 */
  private int summaryMaximumMergeGapSeconds = 300;

  /** 主要な確信度判定の境界値。 */
  private double primaryConfidenceThreshold = .5;

  /** 要約要点Switch秒数。 */
  private int summaryBriefSwitchSeconds = 120;

  /** タイムゾーン。 */
  private String zone = java.time.ZoneId.systemDefault().getId();

  /** 対象外プロセス一覧。 */
  private List<String> excludedProcesses = List.of("KeePassXC.exe", "1Password.exe");

  /** 対象外範囲タイトル照合規則一覧。 */
  private List<String> excludedWindowTitlePatterns =
      List.of("*Password*", "*Private Browsing*", "*InPrivate*");

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (temporal == null
        || temporal.windowSeconds < 60
        || temporal.windowSeconds > 600
        || temporal.intervalSeconds < 60
        || temporal.intervalSeconds > 300
        || temporal.maxRecords < 1
        || temporal.maxRecords > 120
        || temporal.maxInputChars < 1000
        || temporal.maxInputChars > 16000
        || temporal.maxOutputTokens < 1
        || temporal.maxOutputTokens > 2048
        || temporal.maxTotalTokens < 1
        || temporal.maxTotalTokens > 32768
        || temporal.maxRetries < 0
        || temporal.maxRetries > 1
        || temporal.timeoutSeconds < 1
        || temporal.timeoutSeconds > 60)
      throw new IllegalArgumentException("Invalid temporal Activity limits");
    if (visionQueue == null
        || visionQueue.minimumStartIntervalSeconds < 0
        || visionQueue.maxRefreshIntervalSeconds < 1
        || visionQueue.maxRefreshIntervalSeconds < visionQueue.minimumStartIntervalSeconds)
      throw new IllegalArgumentException("Invalid activity vision queue intervals");
    if (observation == null
        || observation.intervalSeconds < 1
        || observation.maxObservationIntervalSeconds < observation.intervalSeconds)
      throw new IllegalArgumentException("Invalid activity observation intervals");
    if (coaching == null
        || coaching.checkIntervalSeconds < 60
        || coaching.checkIntervalSeconds > 86400)
      throw new IllegalArgumentException("Invalid period coaching interval (60..86400 seconds)");
    if (summary == null
        || summary.timeoutSeconds < 1
        || summary.timeoutSeconds > 120
        || summary.projectAliasesFile == null
        || summary.projectAliasesFile.isBlank())
      throw new IllegalArgumentException("Invalid daily summary settings");
    if (classification == null
        || classification.userRulesFile == null
        || classification.userRulesFile.isBlank()
        || classification.unknownRegistry == null
        || classification.entertainmentRegistry == null
        || classification.ruleSuggestion == null
        || classification.diagnostics == null
        || classification.ruleSuggestion.minimumSamples < 1)
      throw new IllegalArgumentException("Invalid classification toolkit settings");
    if (classification.ruleSuggestion.maxOutputTokens < 1)
      throw new IllegalArgumentException(
          "rei.activity.classification.rule-suggestion.max-output-tokens must be positive");
    for (var registry :
        List.of(classification.unknownRegistry, classification.entertainmentRegistry))
      if (registry.retentionDays < 1 || registry.maxEntries < 1 || registry.maxEntries > 10000)
        throw new IllegalArgumentException("Invalid registry retention");
    if (detection == null
        || detection.mode == null
        || !Double.isFinite(detection.skipVisionConfidence)
        || detection.skipVisionConfidence < 0
        || detection.skipVisionConfidence > 1
        || detection.maxOutputTokens < 1)
      throw new IllegalArgumentException("Invalid activity detection settings");
    if (captureIntervalSeconds < 1
        || backgroundAnalysisIntervalSeconds < 1
        || screenshotRetentionDays < 0
        || sessionGapSeconds < 0
        || !Double.isFinite(visionImageScale)
        || visionImageScale <= 0
        || visionImageScale > 1
        || (summaryGapSeconds != null && summaryGapSeconds < 0)
        || summaryBriefSwitchSeconds < 0
        || summaryNormalMergeGapSeconds < 0
        || summaryMaximumMergeGapSeconds < summaryNormalMergeGapSeconds
        || !Double.isFinite(primaryConfidenceThreshold)
        || primaryConfidenceThreshold < 0
        || primaryConfidenceThreshold > 1
        || !Double.isFinite(changeThreshold)
        || changeThreshold < 0
        || changeThreshold > 1
        || excludedProcesses == null
        || excludedWindowTitlePatterns == null)
      throw new IllegalArgumentException("Invalid rei.activity settings");
    if (desktopContext == null
        || desktopContext.monitors == null
        || desktopContext.masks == null
        || desktopContext.monitors.size() > 16
        || desktopContext.masks.size() > 64
        || desktopContext.monitors.stream().anyMatch(id -> id == null || id.isBlank())
        || desktopContext.masks.stream()
            .anyMatch(
                m ->
                    m == null
                        || m.monitor() == null
                        || m.monitor().isBlank()
                        || m.width() <= 0
                        || m.height() <= 0))
      throw new IllegalArgumentException("Invalid desktop privacy settings");
    java.time.ZoneId.of(zone);
  }

  /** 作業状況の把握で用いる実際に使う最大間隔秒数を返す。 */
  public int effectiveMaximumGapSeconds() {
    return summaryGapSeconds == null
        ? summaryMaximumMergeGapSeconds
        : Math.min(summaryGapSeconds, summaryMaximumMergeGapSeconds);
  }

  /** 作業状況の把握で用いる実際に使う通常間隔秒数を返す。 */
  public int effectiveNormalGapSeconds() {
    return Math.min(summaryNormalMergeGapSeconds, effectiveMaximumGapSeconds());
  }
}
