package dev.mikoto2000.rei.computeruse;
public record RobotScreenCapture(RobotDriver driver) implements ScreenCapture {
  public CapturedScreen captureScreen() throws Exception {
    var geometry = currentDisplays(driver);
    var images = new java.util.ArrayList<DisplayCapture>();
    for (var display : geometry) images.add(new DisplayCapture(display, driver.capture(display.bounds())));
    if (!geometry.equals(currentDisplays(driver))) throw new IllegalStateException("Display changed during capture");
    return new CapturedScreen(images);
  }
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
