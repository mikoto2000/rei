package dev.mikoto2000.rei.activity;
import dev.mikoto2000.rei.computeruse.CapturedScreen;
public interface DesktopActivityObserver {
  ForegroundWindow foreground() throws Exception;
  CapturedScreen capture() throws Exception;
}
