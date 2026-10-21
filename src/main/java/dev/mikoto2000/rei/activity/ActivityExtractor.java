package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.CapturedScreen;

/** 作業状況に必要な情報を入力から取り出す。 */
public interface ActivityExtractor {
  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  Result extract(CapturedScreen screen, ForegroundWindow foreground) throws Exception;

  /**
   * 作業状況の把握で受け渡す結果を表す。
   *
   * @param inference 推定
   * @param confidence 確信度
   */
  record Result(ActivityRecord.Inference inference, double confidence) {}
}
