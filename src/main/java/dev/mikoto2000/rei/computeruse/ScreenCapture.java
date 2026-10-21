package dev.mikoto2000.rei.computeruse;

/** 画面操作の支援に必要な画面取得記録を扱う。 */
@FunctionalInterface
public interface ScreenCapture {
  /** 後から状況を確認できるよう、画面を記録する。 */
  CapturedScreen captureScreen() throws Exception;
}
