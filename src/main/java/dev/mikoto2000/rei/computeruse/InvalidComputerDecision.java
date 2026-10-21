package dev.mikoto2000.rei.computeruse;

/**
 * 画面操作の支援に必要な不正画面操作判断結果を扱う。
 *
 * <p>Contains only local validation diagnostics, never provider output or typed text.
 */
final class InvalidComputerDecision extends IllegalArgumentException {
  /** 指定された値や利用先を使って、InvalidComputerDecisionを初期化する。 */
  InvalidComputerDecision(String diagnostic) {
    super(diagnostic);
  }
}
