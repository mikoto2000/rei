package dev.mikoto2000.rei.computeruse;

/** 画面操作の支援に必要な画面操作入力を扱う。 */
@FunctionalInterface
public interface ComputerInput {
  /** 要求された処理を実行する。 */
  void execute(ComputerAction action, CapturedScreen screen) throws Exception;
}
