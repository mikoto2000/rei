package dev.mikoto2000.rei.computeruse;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * 画面操作の支援に必要なAwtRobot操作の実行先を扱う。
 *
 * <p>Lazy: enabling the tool does not capture or move the desktop during application startup.
 */
public final class AwtRobotDriver implements RobotDriver {
  /** デスクトップの入力操作を実行する窓口。 */
  private Robot robot;

  /** 画面操作の支援で用いる実行環境を返す。 */
  private GraphicsEnvironment environment() {
    if (!System.getProperty("os.name", "").startsWith("Windows")
        || GraphicsEnvironment.isHeadless())
      throw new IllegalStateException("Computer Use requires an interactive Windows desktop");
    return GraphicsEnvironment.getLocalGraphicsEnvironment();
  }

  /** 画面操作の支援で用いる画面上の位置と大きさを返す。 */
  public ScreenGeometry geometry() throws AWTException {
    return displays().stream().filter(ScreenGeometry::primary).findFirst().orElseThrow();
  }

  /** 画面操作の支援で用いる画面一覧を返す。 */
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
      result.add(
          new ScreenGeometry(
              monitor.getIDstring(),
              config.getBounds(),
              virtual,
              monitor.equals(environment.getDefaultScreenDevice()),
              transform.getScaleX(),
              transform.getScaleY()));
    }
    result.sort(java.util.Comparator.comparing(ScreenGeometry::id));
    return java.util.List.copyOf(result);
  }

  /** 条件に合う対象を利用するため、表示を選択する。 */
  public void selectDisplay(ScreenGeometry geometry) throws AWTException {
    for (var monitor : environment().getScreenDevices()) {
      if (monitor.getIDstring().equals(geometry.id())) {
        robot = new Robot(monitor);
        return;
      }
    }
    throw new IllegalStateException("Selected display disconnected");
  }

  /** 後から状況を確認できるよう、AwtRobot操作の実行先を記録する。 */
  public BufferedImage capture(Rectangle bounds) throws AWTException {
    var display =
        displays().stream().filter(g -> g.bounds().equals(bounds)).findFirst().orElseThrow();
    selectDisplay(display);
    // Native-resolution variant retains detail on HiDPI monitors; mapping uses its actual
    // dimensions.
    var variants = robot.createMultiResolutionScreenCapture(bounds).getResolutionVariants();
    var image =
        variants.stream()
            .max(
                java.util.Comparator.comparingLong(
                    i -> (long) i.getWidth(null) * i.getHeight(null)))
            .orElseThrow();
    if (image instanceof BufferedImage buffered) return buffered;
    var buffered =
        new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
    var graphics = buffered.createGraphics();
    try {
      graphics.drawImage(image, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return buffered;
  }

  /** 画面操作の支援に関するmoveを処理する。 */
  public void move(int x, int y) {
    robot.mouseMove(x, y);
  }

  /** 画面操作の支援に関するmousePressを処理する。 */
  public void mousePress(int mask) {
    robot.mousePress(mask);
  }

  /** 画面操作の支援に関するmouse解放を処理する。 */
  public void mouseRelease(int mask) {
    robot.mouseRelease(mask);
  }

  /** 画面操作の支援に関するキーPressを処理する。 */
  public void keyPress(int code) {
    robot.keyPress(code);
  }

  /** 画面操作の支援に関するキー解放を処理する。 */
  public void keyRelease(int code) {
    robot.keyRelease(code);
  }

  /** 画面操作の支援に関するwheelを処理する。 */
  public void wheel(int amount) {
    robot.mouseWheel(amount);
  }
}
