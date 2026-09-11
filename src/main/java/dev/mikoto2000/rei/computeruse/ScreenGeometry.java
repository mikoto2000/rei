package dev.mikoto2000.rei.computeruse;
import java.awt.Rectangle;
public record ScreenGeometry(String id, Rectangle bounds, Rectangle virtualBounds, boolean primary,
    double scaleX, double scaleY) {
  public ScreenGeometry(Rectangle bounds, Rectangle virtualBounds, boolean primary) {
    this(primary ? "primary" : "secondary", bounds, virtualBounds, primary, 1, 1);
  }
  public ScreenGeometry {
    if (id == null || id.isBlank() || bounds.width <= 0 || bounds.height <= 0
        || !Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0 || scaleY <= 0)
      throw new IllegalArgumentException("Invalid display geometry");
    bounds = new Rectangle(bounds); virtualBounds = new Rectangle(virtualBounds);
  }
  @Override public Rectangle bounds() { return new Rectangle(bounds); }
  @Override public Rectangle virtualBounds() { return new Rectangle(virtualBounds); }
  public void requirePrimary() {
    if (!primary || bounds.width < 1 || bounds.height < 1 || !virtualBounds.contains(bounds))
      throw new IllegalArgumentException("Only the current primary monitor is supported");
  }
}
