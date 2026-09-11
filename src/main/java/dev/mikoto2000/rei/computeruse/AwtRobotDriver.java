package dev.mikoto2000.rei.computeruse;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Lazy: enabling the tool does not capture or move the desktop during application startup. */
public final class AwtRobotDriver implements RobotDriver {
  private Robot robot;
  private GraphicsDevice device;
  private void initialize() throws AWTException {
    if (robot != null) return;
    if (!System.getProperty("os.name", "").startsWith("Windows") || GraphicsEnvironment.isHeadless())
      throw new IllegalStateException("Computer Use requires an interactive Windows desktop");
    device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    robot = new Robot(device);
  }
  public ScreenGeometry geometry() throws AWTException {
    initialize();
    var environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
    Rectangle virtual = null;
    for (var monitor : environment.getScreenDevices()) {
      var bounds = monitor.getDefaultConfiguration().getBounds();
      virtual = virtual == null ? new Rectangle(bounds) : virtual.union(bounds);
    }
    return new ScreenGeometry(device.getDefaultConfiguration().getBounds(), virtual,
        device.equals(environment.getDefaultScreenDevice()));
  }
  public BufferedImage capture(Rectangle bounds) throws AWTException { initialize(); return robot.createScreenCapture(bounds); }
  public void move(int x, int y) { robot.mouseMove(x, y); }
  public void mousePress(int mask) { robot.mousePress(mask); }
  public void mouseRelease(int mask) { robot.mouseRelease(mask); }
  public void keyPress(int code) { robot.keyPress(code); }
  public void keyRelease(int code) { robot.keyRelease(code); }
  public void wheel(int amount) { robot.mouseWheel(amount); }
}
