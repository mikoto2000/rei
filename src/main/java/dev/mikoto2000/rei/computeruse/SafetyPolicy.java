package dev.mikoto2000.rei.computeruse;

/** Safetyの判断基準をまとめる。 */
@FunctionalInterface
public interface SafetyPolicy {
  /** 画面操作の支援において、allowsに該当するか判定する。 */
  boolean allows(ComputerAction action);

  /** 画面操作の支援で用いるlowリスク限定を返す。 */
  static SafetyPolicy lowRiskOnly() {
    return action -> action.risk() == ComputerAction.Risk.LOW;
  }

  /** 画面操作の支援で用いる自動実行の指定を返す。 */
  static SafetyPolicy fullAuto() {
    return action ->
        action.risk() == ComputerAction.Risk.LOW
            || action.risk() == ComputerAction.Risk.CONFIRM_REQUIRED;
  }
}
