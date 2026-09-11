package dev.mikoto2000.rei.computeruse;
public record FixedUiStabilizer(Sleeper sleeper, long delayMillis) implements UiStabilizer {
  public FixedUiStabilizer {
    if (delayMillis < 1 || delayMillis > 10000) throw new IllegalArgumentException("Invalid stabilization delay");
  }
  public void awaitAfter(ComputerAction action) throws InterruptedException {
    sleeper.sleep(action instanceof ComputerAction.Wait wait ? wait.millis() : delayMillis);
  }
}
