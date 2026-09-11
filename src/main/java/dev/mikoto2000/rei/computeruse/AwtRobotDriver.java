package dev.mikoto2000.rei.computeruse;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Lazy: enabling the tool does not capture or move the desktop during application startup. */
public final class AwtRobotDriver implements RobotDriver {
  private Robot robot;
  private GraphicsEnvironment environment() {
    if (!System.getProperty("os.name", "").startsWith("Windows") || GraphicsEnvironment.isHeadless())
      throw new IllegalStateException("Computer Use requires an interactive Windows desktop");
    return GraphicsEnvironment.getLocalGraphicsEnvironment();
  }
  public ScreenGeometry geometry() throws AWTException {
    return displays().stream().filter(ScreenGeometry::primary).findFirst().orElseThrow();
  }
  public java.util.List<ScreenGeometry> displays() {
    var environment = environment();
    Rectangle virtual = null;
    for (var monitor : environment.getScreenDevices()) {
      var bounds = monitor.getDefaultConfiguration().getBounds();
      virtual = virtual == null ? new Rectangle(bounds) : virtual.union(bounds);
    }
    var result = new java.util.ArrayList<ScreenGeometry>();
    for (var monitor : environment.getScreenDevices()) {
      var config = monitor.getDefaultConfiguration();
      var transform = config.getDefaultTransform();
      result.add(new ScreenGeometry(monitor.getIDstring(), config.getBounds(), virtual,
          monitor.equals(environment.getDefaultScreenDevice()), transform.getScaleX(), transform.getScaleY()));
    }
    result.sort(java.util.Comparator.comparing(ScreenGeometry::id));
    return java.util.List.copyOf(result);
  }
  public void selectDisplay(ScreenGeometry geometry) throws AWTException {
    for (var monitor : environment().getScreenDevices()) {
      if (monitor.getIDstring().equals(geometry.id())) {
        robot = new Robot(monitor);
        return;
      }
    }
    throw new IllegalStateException("Selected display disconnected");
  }
  public BufferedImage capture(Rectangle bounds) throws AWTException {
    var display = displays().stream().filter(g -> g.bounds().equals(bounds)).findFirst().orElseThrow();
    selectDisplay(display);
    // Native-resolution variant retains detail on HiDPI monitors; mapping uses its actual dimensions.
    var variants = robot.createMultiResolutionScreenCapture(bounds).getResolutionVariants();
    var image = variants.stream().max(java.util.Comparator.comparingLong(i -> (long)i.getWidth(null) * i.getHeight(null))).orElseThrow();
    if (image instanceof BufferedImage buffered) return buffered;
    var buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
    var graphics = buffered.createGraphics();
    try { graphics.drawImage(image,0,0,null); } finally { graphics.dispose(); }
    return buffered;
  }
  public void move(int x, int y) { robot.mouseMove(x, y); }
  public void mousePress(int mask) { robot.mousePress(mask); }
  public void mouseRelease(int mask) { robot.mouseRelease(mask); }
  public void keyPress(int code) { robot.keyPress(code); }
  public void keyRelease(int code) { robot.keyRelease(code); }
  public void wheel(int amount) { robot.mouseWheel(amount); }
}
