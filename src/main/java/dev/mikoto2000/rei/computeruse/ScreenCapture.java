package dev.mikoto2000.rei.computeruse;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 画面操作の支援に必要な画面取得記録を扱う。 */
@FunctionalInterface
public interface ScreenCapture {
  /** 後から状況を確認できるよう、画面を記録する。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Capture backends can supply implementation-specific checked failures.")
  CapturedScreen captureScreen() throws Exception;
}
