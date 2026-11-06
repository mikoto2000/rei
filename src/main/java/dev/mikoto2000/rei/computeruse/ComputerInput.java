package dev.mikoto2000.rei.computeruse;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 画面操作の支援に必要な画面操作入力を扱う。 */
@FunctionalInterface
public interface ComputerInput {
  /** 要求された処理を実行する。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Input backends can supply implementation-specific checked failures.")
  void execute(ComputerAction action, CapturedScreen screen) throws Exception;
}
