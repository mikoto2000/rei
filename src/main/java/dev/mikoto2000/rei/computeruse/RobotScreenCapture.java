package dev.mikoto2000.rei.computeruse;
public record RobotScreenCapture(RobotDriver driver) implements ScreenCapture {
  public CapturedScreen captureScreen() throws Exception {
    var geometry = driver.geometry();
    geometry.requirePrimary();
    var image = driver.capture(geometry.bounds());
    if (!geometry.equals(driver.geometry())) throw new IllegalStateException("Display changed during capture");
    return new CapturedScreen(image, geometry.bounds());
  }
}
