package dev.mikoto2000.rei.computeruse;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public record CapturedScreen(java.util.List<DisplayCapture> displays) {
  public CapturedScreen {
    displays = java.util.List.copyOf(displays);
    if (displays.isEmpty() || displays.stream().map(d -> d.geometry().id()).distinct().count() != displays.size())
      throw new IllegalArgumentException("Missing or duplicate displays");
  }
  public CapturedScreen(BufferedImage image, Rectangle bounds) {
    this(java.util.List.of(new DisplayCapture(new ScreenGeometry(bounds, bounds, true), image)));
  }
  public BufferedImage image() { return displays.getFirst().image(); }
  public Rectangle bounds() { return displays.getFirst().geometry().bounds(); }
  public DisplayCapture display(String id) {
    if (id == null && displays.size() == 1) return displays.getFirst();
    return displays.stream().filter(d -> d.geometry().id().equals(id)).findFirst()
        .orElseThrow(() -> new InvalidComputerDecision("Missing or unknown target displayId"));
  }
}
