package dev.mikoto2000.rei.computeruse;

/**
 * 画面操作の支援で受け渡す固定表示Stabilizerを表す。
 *
 * @param sleeper 待機の実行先
 * @param delayMillis 遅延時間ミリ秒数
 */
public record FixedUiStabilizer(Sleeper sleeper, long delayMillis) implements UiStabilizer {
  /** 受け取った情報からFixedUiStabilizerを作成する。 */
  public FixedUiStabilizer {
    if (delayMillis < 1 || delayMillis > 10000)
      throw new IllegalArgumentException("Invalid stabilization delay");
  }

  /** 必要な状態が整ってから先へ進むため、変更後を待つ。 */
  public void awaitAfter(ComputerAction action) throws InterruptedException {
    sleeper.sleep(action instanceof ComputerAction.Wait wait ? wait.millis() : delayMillis);
  }
}
