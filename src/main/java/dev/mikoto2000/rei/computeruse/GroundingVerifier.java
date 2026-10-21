package dev.mikoto2000.rei.computeruse;

/** 画面操作の支援に必要なGrounding確認の窓口を扱う。 */
@FunctionalInterface
interface GroundingVerifier {
  /**
   * 要求された条件を満たすことを確認する。
   *
   * <p>point is null when checking target visibility; otherwise it is normalized to image.
   */
  void verify(
      ComputerObservation observation,
      java.awt.image.BufferedImage image,
      String target,
      double[] point)
      throws Exception;

  /** 要求された条件を満たすか判断するため、Pointを確認する。 */
  default void verifyPoint(
      ComputerObservation observation, DisplayCapture display, String target, double[] point)
      throws Exception {
    verify(observation, display.image(), target, point);
  }
}
