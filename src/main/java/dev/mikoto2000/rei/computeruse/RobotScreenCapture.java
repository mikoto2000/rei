package dev.mikoto2000.rei.computeruse;

/**
 * 画面操作の支援で受け渡すRobot画面取得記録を表す。
 *
 * @param driver 操作の実行先
 */
public record RobotScreenCapture(RobotDriver driver) implements ScreenCapture {
  /** 後から状況を確認できるよう、画面を記録する。 */
  public CapturedScreen captureScreen() throws Exception {
    var geometry = currentDisplays(driver);
    var images = new java.util.ArrayList<DisplayCapture>();
    for (var display : geometry)
      images.add(new DisplayCapture(display, driver.capture(display.bounds())));
    if (!geometry.equals(currentDisplays(driver)))
      throw new IllegalStateException("Display changed during capture");
    return new CapturedScreen(images);
  }

  /** 画面操作の支援で用いる現在画面一覧を返す。 */
  static java.util.List<ScreenGeometry> currentDisplays(RobotDriver driver) throws Exception {
    var displays = driver.displays();
    if (displays == null || displays.isEmpty()) {
      var geometry = driver.geometry();
      geometry.requirePrimary();
      return java.util.List.of(geometry);
    }
    return java.util.List.copyOf(displays);
  }
}
