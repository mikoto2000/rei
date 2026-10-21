package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握で受け渡す前面ウィンドウを表す。
 *
 * <p>OS evidence, never supplied by Vision.
 *
 * @param processName プロセス名前
 * @param processId プロセス識別子
 * @param windowTitle 範囲タイトル
 * @param windowId 範囲識別子
 * @param bounds 許容範囲
 */
public record ForegroundWindow(
    String processName,
    long processId,
    String windowTitle,
    String windowId,
    ActivityRecord.Bounds bounds) {
  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ForegroundWindow(String processName, long processId, String windowTitle, String windowId) {
    this(processName, processId, windowTitle, windowId, null);
  }
}
