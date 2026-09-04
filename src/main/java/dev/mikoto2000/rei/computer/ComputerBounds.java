package dev.mikoto2000.rei.computer;

import java.awt.Point;

public record ComputerBounds(int x, int y, int width, int height) {
  public boolean valid() {
    return width > 0 && height > 0;
  }

  public Point center() {
    return new Point(x + width / 2, y + height / 2);
  }
}
