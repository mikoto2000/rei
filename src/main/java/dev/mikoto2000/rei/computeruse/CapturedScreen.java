package dev.mikoto2000.rei.computeruse;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public record CapturedScreen(BufferedImage image, Rectangle bounds) {
  public CapturedScreen {
    if (image == null || bounds == null || bounds.width <= 0 || bounds.height <= 0)
      throw new IllegalArgumentException("Invalid capture");
    bounds = new Rectangle(bounds);
  }
  @Override public Rectangle bounds() { return new Rectangle(bounds); }
}
