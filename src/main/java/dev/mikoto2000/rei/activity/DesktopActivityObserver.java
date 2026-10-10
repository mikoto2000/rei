package dev.mikoto2000.rei.activity;
import dev.mikoto2000.rei.computeruse.CapturedScreen;
public interface DesktopActivityObserver {
  ForegroundWindow foreground() throws Exception;
  CapturedScreen capture() throws Exception;
  /** Session-local timing and identity only; never input contents. Null means unavailable. */
  default Lightweight lightweight() throws Exception {return null;}
  record Lightweight(long lastInput,long uptimeMillis,String windowId,String desktop,boolean locked,boolean reliable,long workingMillis) {
    public Lightweight(long input,long uptime,String window,String desktop,boolean locked,boolean reliable) {
      this(input,uptime,window,desktop,locked,reliable,uptime);
    }
  }
  default Metadata metadata() throws Exception {return new Metadata(foreground(),java.util.List.of());}
  record Metadata(ForegroundWindow foreground,java.util.List<ActivityEvidence.VisibleWindow> visibleWindows) {}
}
