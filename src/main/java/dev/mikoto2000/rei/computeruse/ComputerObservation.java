package dev.mikoto2000.rei.computeruse;

import java.util.List;

/**
 * 画面操作の支援で受け渡す画面操作観測内容を表す。
 *
 * @param goal 目標
 * @param screenshot 画面画像
 * @param recentHistory 直近履歴
 * @param step 手順
 * @param maxSteps 最大手順一覧
 * @param diagnosticRun 診断実行
 * @param focusState focus状態
 */
public record ComputerObservation(
    String goal,
    CapturedScreen screenshot,
    List<String> recentHistory,
    int step,
    int maxSteps,
    java.nio.file.Path diagnosticRun,
    String focusState) {
  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ComputerObservation(
      String goal,
      CapturedScreen screenshot,
      List<String> recentHistory,
      int step,
      int maxSteps,
      java.nio.file.Path diagnosticRun) {
    this(
        goal, screenshot, recentHistory, step, maxSteps, diagnosticRun, "{\"status\":\"unknown\"}");
  }

  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ComputerObservation(
      String goal, CapturedScreen screenshot, List<String> recentHistory, int step, int maxSteps) {
    this(goal, screenshot, recentHistory, step, maxSteps, null);
  }

  /** 受け取った情報からComputerObservationを作成する。 */
  public ComputerObservation {
    recentHistory = List.copyOf(recentHistory);
  }
}
