package dev.mikoto2000.rei.computeruse;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
/** Narrow native boundary, permitting adapter tests without constructing Robot. */
public interface RobotDriver {
  ScreenGeometry geometry() throws Exception;
  BufferedImage capture(Rectangle bounds) throws Exception;
  void move(int x, int y);
  void mousePress(int mask);
  void mouseRelease(int mask);
  void keyPress(int code);
  void keyRelease(int code);
  void wheel(int amount);
}
