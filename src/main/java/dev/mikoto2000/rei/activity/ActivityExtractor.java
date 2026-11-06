package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.CapturedScreen;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 作業状況に必要な情報を入力から取り出す。 */
public interface ActivityExtractor {
  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "A vision adapter may report encoding or other checked extraction failures to the capture pipeline.")
  Result extract(CapturedScreen screen, ForegroundWindow foreground) throws Exception;

  /**
   * 作業状況の把握で受け渡す結果を表す。
   *
   * @param inference 推定
   * @param confidence 確信度
   */
  record Result(ActivityRecord.Inference inference, double confidence) {}
}
