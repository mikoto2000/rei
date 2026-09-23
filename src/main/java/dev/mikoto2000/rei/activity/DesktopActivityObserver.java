package dev.mikoto2000.rei.activity;
import dev.mikoto2000.rei.computeruse.CapturedScreen;
public interface DesktopActivityObserver {
  ForegroundWindow foreground() throws Exception;
  CapturedScreen capture() throws Exception;
  default Metadata metadata() throws Exception {return new Metadata(foreground(),java.util.List.of());}
  record Metadata(ForegroundWindow foreground,java.util.List<ActivityEvidence.VisibleWindow> visibleWindows) {}
}
