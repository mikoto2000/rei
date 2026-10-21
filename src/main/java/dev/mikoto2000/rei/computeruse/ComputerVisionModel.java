package dev.mikoto2000.rei.computeruse;

/** 画面操作の支援に必要な画面操作画像認識モデルを扱う。 */
@FunctionalInterface
public interface ComputerVisionModel {
  /** 画面操作の支援で用いる判断を返す。 */
  ComputerAction decide(ComputerObservation observation) throws Exception;
}
