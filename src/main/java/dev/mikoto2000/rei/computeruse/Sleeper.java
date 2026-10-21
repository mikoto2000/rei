package dev.mikoto2000.rei.computeruse;

/** 画面操作の支援に必要な待機の実行先を扱う。 */
@FunctionalInterface
public interface Sleeper {
  /** 画面操作の支援に関する待機を処理する。 */
  void sleep(long millis) throws InterruptedException;
}
