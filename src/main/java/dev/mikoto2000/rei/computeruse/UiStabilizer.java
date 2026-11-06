package dev.mikoto2000.rei.computeruse;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 画面操作の支援に必要な表示Stabilizerを扱う。 */
@FunctionalInterface
public interface UiStabilizer {
  /** 必要な状態が整ってから先へ進むため、変更後を待つ。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Stabilization backends can supply implementation-specific checked failures.")
  void awaitAfter(ComputerAction action) throws Exception;
}
