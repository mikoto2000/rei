package dev.mikoto2000.rei.interest;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 関心の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.interest")
public class InterestProperties {

  /** 有効状態。 */
  private boolean enabled = false;

  /** lookback日数。 */
  private int lookbackDays = 7;

  /** メッセージ上限。 */
  private int messageLimit = 200;

  /** 採用する評価値の下限。 */
  private double minScore = 0.6;

  /** 最大話題一覧。 */
  private int maxTopics = 3;

  /** 直近時間数。 */
  private int recentHours = 24;

  /** ベクトル上位件数。 */
  private int vectorTopK = 3;

  /** Web上位件数。 */
  private int webTopK = 5;

  /** 話題更新実行間隔時間数。 */
  private int topicUpdateIntervalHours = 24;

  /** past検索条件Lookback日数。 */
  private int pastQueryLookbackDays = 7;

  /** Full extraction prompt budget, conservatively measured in UTF-8 token units. */
  private int extractionMaxInputTokens = 24000;

  /** Total streaming request deadline; background work must not wait twenty minutes. */
  private int extractionTimeoutSeconds = 120;

  /** Output generation budget, including provider reasoning when applicable. */
  private int extractionMaxOutputTokens = 4096;

  /** Reject invalid extraction bounds before starting a model request. */
  public void validateExtraction() {
    if (extractionMaxInputTokens < 1024
        || extractionMaxInputTokens > 65536
        || extractionTimeoutSeconds < 1
        || extractionTimeoutSeconds > 120
        || extractionMaxOutputTokens < 64
        || extractionMaxOutputTokens > 4096)
      throw new IllegalArgumentException("Invalid interest extraction budgets");
  }
}
