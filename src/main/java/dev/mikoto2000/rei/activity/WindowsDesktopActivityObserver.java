package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Read-only foreground/visible Win32 metadata probes; pixels use the existing multi-display Robot adapter. */
public final class WindowsDesktopActivityObserver implements DesktopActivityObserver {
  private final ScreenCapture capture=new RobotScreenCapture(new AwtRobotDriver());
  @Override public CapturedScreen capture() throws Exception { return capture.captureScreen(); }
  @Override public Metadata metadata() throws Exception {
    var node=probe("metadata.ps1",1048576);
    if(node==null)return null;
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    return mapper.treeToValue(node,Metadata.class);
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
