package dev.mikoto2000.rei.computeruse;

import java.awt.Point;
import java.awt.image.BufferedImage;

public record DisplayCapture(ScreenGeometry geometry, BufferedImage image) {
  public DisplayCapture {
    if (geometry == null || image == null) throw new IllegalArgumentException("Invalid display capture");
  }
  public Point desktopPoint(ComputerAction.Target target) {
    if (target.x() < 0 || target.y() < 0 || target.x() >= image.getWidth() || target.y() >= image.getHeight())
      throw new InvalidComputerDecision("Target outside selected screenshot");
    var bounds = geometry.bounds();
    return new Point(bounds.x + (int)((long)target.x() * bounds.width / image.getWidth()),
        bounds.y + (int)((long)target.y() * bounds.height / image.getHeight()));
  }
}
