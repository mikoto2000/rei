package dev.mikoto2000.rei.checkpoint;

/** 再開概要の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@org.springframework.stereotype.Component
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "rei.resume.overview")
public class ResumeOverviewProperties {
  /** 有効状態。 */
  private boolean enabled = true;

  /** 失敗最大経過時間日数。 */
  private int failedMaxAgeDays = 30;

  /** 作業の中断と再開において、有効状態かどうかを判定する。 */
  public boolean isEnabled() {
    return enabled;
  }

  /** 作業の中断と再開で使用する有効状態を設定する。 */
  public void setEnabled(boolean value) {
    enabled = value;
  }

  /** 作業の中断と再開で参照する失敗最大経過時間日数を返す。 */
  public int getFailedMaxAgeDays() {
    return failedMaxAgeDays;
  }

  /** 作業の中断と再開で使用する失敗最大経過時間日数を設定する。 */
  public void setFailedMaxAgeDays(int value) {
    if (value < 0) throw new IllegalArgumentException("Negative failed task age");
    failedMaxAgeDays = value;
  }
}
