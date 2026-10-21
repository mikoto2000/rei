package dev.mikoto2000.rei.computeruse;

/** 画面操作の支援に必要な表示Stabilizerを扱う。 */
@FunctionalInterface
public interface UiStabilizer {
  /** 必要な状態が整ってから先へ進むため、変更後を待つ。 */
  void awaitAfter(ComputerAction action) throws Exception;
}
