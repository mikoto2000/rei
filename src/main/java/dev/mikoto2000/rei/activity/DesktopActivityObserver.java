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
  record MonitorIdentity(String id,ActivityRecord.Bounds bounds) {}
  record Metadata(ForegroundWindow foreground,java.util.List<ActivityEvidence.VisibleWindow> visibleWindows,boolean complete,java.util.List<MonitorIdentity> monitors) {
    public Metadata(ForegroundWindow foreground,java.util.List<ActivityEvidence.VisibleWindow> windows,boolean complete){this(foreground,windows,complete,java.util.List.of());}
    public Metadata(ForegroundWindow foreground,java.util.List<ActivityEvidence.VisibleWindow> windows){this(foreground,windows,false);}
  }
}
