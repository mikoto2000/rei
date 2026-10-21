package dev.mikoto2000.rei.activity.behavior;

import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 作業習慣の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Data
@ConfigurationProperties("rei.activity.behavior")
public class BehaviorProperties {
  /** 有効状態。 */
  private boolean enabled = false;

  /** 娯楽分類一覧。 */
  private Set<String> entertainmentCategories = Set.of("social", "media", "shopping", "gaming");

  /** 継続。 */
  private Continuous continuous = new Continuous();

  /** 短期・長期それぞれの作業傾向を判定する期間の設定。 */
  private Windows windows = new Windows();

  /** 中断。 */
  private Interruption interruption = new Interruption();

  /** 通知を控える期間。 */
  private Cooldown cooldown = new Cooldown();

  /** 確認実行間隔秒数。 */
  private int checkIntervalSeconds = 60;

  /** 履歴分数。 */
  private int historyMinutes = 1440;

  /** 作業習慣の支援に必要な継続を扱う。 */
  @Data
  public static class Continuous {
    /** 通知分数／注意事項分数／確かな注意事項分数。 */
    private int noticeMinutes = 30, warningMinutes = 60, strongWarningMinutes = 120;
  }

  /** 作業習慣の支援に必要な範囲を扱う。 */
  @Data
  public static class Window {
    /** 継続時間分数／最小観測分数。 */
    private int durationMinutes, minimumObservedMinutes;

    /** 割合。 */
    private double ratio;

    /** 既定の状態でWindowを初期化する。 */
    public Window() {}

    /** 指定された値や利用先を使って、Windowを初期化する。 */
    public Window(int duration, int minimum, double ratio) {
      this.durationMinutes = duration;
      this.minimumObservedMinutes = minimum;
      this.ratio = ratio;
    }
  }

  /** 短期と長期の判定期間をまとめ、異なる時間幅から作業習慣を判断する。 */
  @Data
  public static class Windows {
    /** 短期と長期の判定期間、および各期間で必要とする観測量。 */
    private Window shortWindow = new Window(60, 30, .5), longWindow = new Window(120, 60, .6);
  }

  /** 作業習慣の支援に必要な中断を扱う。 */
  @Data
  public static class Interruption {
    /** 連続作業の扱いをリセットする作業時間と、短い中断を無視する許容秒数。 */
    private int resetAfterWorkMinutes = 5, noiseToleranceSeconds = 60;
  }

  /** 作業習慣の支援に必要な通知を控える期間を扱う。 */
  @Data
  public static class Cooldown {
    /** 通知分数／注意事項分数／確かな注意事項分数。 */
    private int noticeMinutes = 60, warningMinutes = 45, strongWarningMinutes = 30;
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (entertainmentCategories == null
        || entertainmentCategories.isEmpty()
        || !Set.of("social", "media", "shopping", "gaming").containsAll(entertainmentCategories)
        || continuous == null
        || windows == null
        || windows.shortWindow == null
        || windows.longWindow == null
        || interruption == null
        || cooldown == null
        || checkIntervalSeconds < 1
        || historyMinutes < 1
        || historyMinutes > 10080) throw new IllegalArgumentException("Invalid behavior settings");
    if (continuous.noticeMinutes < 1
        || continuous.warningMinutes < continuous.noticeMinutes
        || continuous.strongWarningMinutes < continuous.warningMinutes
        || interruption.resetAfterWorkMinutes < 1
        || interruption.noiseToleranceSeconds < 0
        || cooldown.noticeMinutes < 1
        || cooldown.warningMinutes < 1
        || cooldown.strongWarningMinutes < 1)
      throw new IllegalArgumentException("Invalid behavior thresholds");
    // 十分な観測時間に基づいて習慣を判断できるよう、判定期間と必要な観測量の整合を確認する。
    for (var w : java.util.List.of(windows.shortWindow, windows.longWindow)) {
      if (w.durationMinutes < 1
          || w.minimumObservedMinutes < 1
          || w.minimumObservedMinutes > w.durationMinutes
          || !Double.isFinite(w.ratio)
          || w.ratio <= 0
          || w.ratio > 1
          || historyMinutes < w.durationMinutes)
        throw new IllegalArgumentException("Invalid behavior window");
    }
    // 助言の判断に必要な期間よりも短い履歴設定を受け付けない。
    if (historyMinutes < continuous.strongWarningMinutes
        || historyMinutes < interruption.resetAfterWorkMinutes)
      throw new IllegalArgumentException("Behavior history is too short");
  }

  /** 作業習慣の支援で用いる通知を控える期間秒数を返す。 */
  public long cooldownSeconds(BehaviorSeverity s) {
    return 60L
        * switch (s) {
          case NONE -> 0;
          case NOTICE -> cooldown.noticeMinutes;
          case WARNING -> cooldown.warningMinutes;
          case STRONG_WARNING -> cooldown.strongWarningMinutes;
        };
  }
}
