package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Read-only foreground/visible Win32 metadata probes; pixels use the existing multi-display Robot adapter. */
public final class WindowsDesktopActivityObserver implements DesktopActivityObserver {
  @Override public Lightweight lightweight(){return WindowsInputProbe.read();}
  private final ActivityProperties properties;
  private volatile Metadata lastMetadata;
  public WindowsDesktopActivityObserver(){this(null);}
  public WindowsDesktopActivityObserver(ActivityProperties properties){this.properties=properties;}
  private final ScreenCapture capture=new RobotScreenCapture(new AwtRobotDriver());
  @Override public CapturedScreen capture() throws Exception {
    var metadata=lastMetadata;
    boolean identified=properties!=null && properties.getDetection().isBackgroundFullScreenEnabled();
    if(identified && (metadata==null || !metadata.complete()))throw new IllegalStateException("Desktop identity unavailable");
    var screen=capture.captureScreen();
    return identified?identify(screen,metadata.monitors()):screen;
  }
  static CapturedScreen identify(CapturedScreen screen,java.util.List<MonitorIdentity> monitors) {
    if(monitors==null || monitors.isEmpty() || monitors.size()>16)throw new IllegalStateException("Desktop identity unavailable");
    var mapped=new java.util.ArrayList<DisplayCapture>();
    for(var display:screen.displays()) {
      var g=display.geometry();var b=ActivityImages.physicalBounds(g);
      var matches=monitors.stream().filter(m->m!=null && m.bounds()!=null && m.id()!=null && !m.id().isBlank()
          && m.bounds().x()==b.x && m.bounds().y()==b.y && m.bounds().width()==b.width && m.bounds().height()==b.height).toList();
      if(matches.size()!=1)throw new IllegalStateException("Desktop identity ambiguous");
      mapped.add(new DisplayCapture(new ScreenGeometry(matches.getFirst().id(),g.bounds(),g.virtualBounds(),g.primary(),g.scaleX(),g.scaleY()),display.image()));
    }
    if(mapped.stream().map(d->d.geometry().id()).distinct().count()!=mapped.size())throw new IllegalStateException("Desktop identity duplicated");
    return new CapturedScreen(mapped);
  }
  @Override public Metadata metadata() throws Exception {
    var node=probe("metadata.ps1",1048576);
    if(node==null)return null;
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    var result=mapper.treeToValue(node,Metadata.class);lastMetadata=result;return result;
  }
  @Override public ForegroundWindow foreground() throws Exception {
    var node=probe("foreground.ps1",32768);
    if(node==null || !node.path("processName").isTextual() || !node.path("windowTitle").isTextual()
        || !node.path("windowId").isTextual() || !node.path("processId").isIntegralNumber())return null;
    return new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(node,ForegroundWindow.class);
  }
  private com.fasterxml.jackson.databind.JsonNode probe(String resource,long maxBytes) throws Exception {
    if(!System.getProperty("os.name","").startsWith("Windows")) return null;
    String script;
    try(var in=getClass().getResourceAsStream("/activity/"+resource)) {
      script=new String(java.util.Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
    }
    Path output=Files.createTempFile("rei-activity-foreground-",".json");
    Process process=null;
    try {
      var executable=Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe");
      process=new ProcessBuilder(executable.toString(),"-NoProfile","-NonInteractive","-WindowStyle","Hidden","-EncodedCommand",
          Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
          .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      if(!process.waitFor(5,TimeUnit.SECONDS) || process.exitValue()!=0 || Files.size(output)>maxBytes) return null;
      var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper.readTree(Files.readString(output,StandardCharsets.UTF_8).strip());
    } catch(InterruptedException e) { Thread.currentThread().interrupt();throw e; }
    finally { if(process!=null && process.isAlive())process.destroyForcibly();Files.deleteIfExists(output); }
  }
}
