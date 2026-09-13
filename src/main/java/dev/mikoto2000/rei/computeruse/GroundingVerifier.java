package dev.mikoto2000.rei.computeruse;

@FunctionalInterface
interface GroundingVerifier {
  /** point is null when checking target visibility; otherwise it is normalized to image. */
  void verify(ComputerObservation observation, java.awt.image.BufferedImage image,
      String target, double[] point) throws Exception;
  default void verifyPoint(ComputerObservation observation, DisplayCapture display, String target, double[] point) throws Exception {
    verify(observation,display.image(),target,point);
  }
}
