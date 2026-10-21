package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.CapturedScreen;

/** デスクトップ作業状況の状況を観測し、記録や判断に必要な情報を提供する。 */
public interface DesktopActivityObserver {
  /** 作業状況の把握で用いる前面を返す。 */
  ForegroundWindow foreground() throws Exception;

  /** 後から状況を確認できるよう、デスクトップ作業状況観測を記録する。 */
  CapturedScreen capture() throws Exception;

  /**
   * 作業状況の把握で用いるlightweightを返す。
   *
   * <p>Session-local timing and identity only; never input contents. Null means unavailable.
   */
  default Lightweight lightweight() throws Exception {
    return null;
  }

  /**
   * 作業状況の把握で受け渡すLightweightを表す。
   *
   * @param lastInput 直近入力
   * @param uptimeMillis uptimeミリ秒数
   * @param windowId 範囲識別子
   * @param desktop デスクトップ
   * @param locked 使用中
   * @param reliable 信頼できるかどうか
   * @param workingMillis 作業中ミリ秒数
   */
  record Lightweight(
      long lastInput,
      long uptimeMillis,
      String windowId,
      String desktop,
      boolean locked,
      boolean reliable,
      long workingMillis) {
    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Lightweight(
        long input, long uptime, String window, String desktop, boolean locked, boolean reliable) {
      this(input, uptime, window, desktop, locked, reliable, uptime);
    }
  }

  /** 作業状況の把握で用いる付随情報を返す。 */
  default Metadata metadata() throws Exception {
    return new Metadata(foreground(), java.util.List.of());
  }

  /**
   * 作業状況の把握で受け渡す排他制御識別情報を表す。
   *
   * @param id 識別子
   * @param bounds 許容範囲
   */
  record MonitorIdentity(String id, ActivityRecord.Bounds bounds) {}

  /**
   * 作業状況の把握で受け渡す付随情報を表す。
   *
   * @param foreground 前面
   * @param visibleWindows 表示対象Windows
   * @param complete 完了
   * @param monitors 監視の窓口一覧
   */
  record Metadata(
      ForegroundWindow foreground,
      java.util.List<ActivityEvidence.VisibleWindow> visibleWindows,
      boolean complete,
      java.util.List<MonitorIdentity> monitors) {
    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Metadata(
        ForegroundWindow foreground,
        java.util.List<ActivityEvidence.VisibleWindow> windows,
        boolean complete) {
      this(foreground, windows, complete, java.util.List.of());
    }

    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Metadata(
        ForegroundWindow foreground, java.util.List<ActivityEvidence.VisibleWindow> windows) {
      this(foreground, windows, false);
    }
  }
}
