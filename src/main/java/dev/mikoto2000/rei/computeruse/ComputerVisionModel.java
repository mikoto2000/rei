package dev.mikoto2000.rei.computeruse;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 画面操作の支援に必要な画面操作画像認識モデルを扱う。 */
@FunctionalInterface
public interface ComputerVisionModel {
  /** 画面操作の支援で用いる判断を返す。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Model backends can supply implementation-specific checked failures.")
  ComputerAction decide(ComputerObservation observation) throws Exception;
}
