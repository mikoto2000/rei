package dev.mikoto2000.rei.computeruse;
import java.awt.Rectangle;
public record ScreenGeometry(Rectangle bounds, Rectangle virtualBounds, boolean primary) {
  public ScreenGeometry { bounds = new Rectangle(bounds); virtualBounds = new Rectangle(virtualBounds); }
  @Override public Rectangle bounds() { return new Rectangle(bounds); }
  @Override public Rectangle virtualBounds() { return new Rectangle(virtualBounds); }
  public void requirePrimary() {
    if (!primary || bounds.width < 1 || bounds.height < 1 || !virtualBounds.contains(bounds))
      throw new IllegalArgumentException("Only the current primary monitor is supported");
  }
}
