package dev.mikoto2000.rei.workcontext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Optional read-only work-time metadata. Git failure never prevents reading a handoff. */
@Component
public class WorkContextGit {
  public WorkContext.GitState capture(Path root,Instant now) {
    return new WorkContext.GitState(root.toString(),read(root,"--abbrev-ref","HEAD"),read(root,"HEAD"),now);
  }
  private String read(Path root,String... args) {
    Process process=null;
    try {
      var command=new java.util.ArrayList<>(java.util.List.of("git","-C",root.toString(),"rev-parse"));command.addAll(java.util.List.of(args));
      process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      if(!process.waitFor(2,TimeUnit.SECONDS)) return null;
      if(process.exitValue()!=0) return null;
      String value=new String(process.getInputStream().readNBytes(1024),java.nio.charset.StandardCharsets.UTF_8).strip();
      return value.isBlank()?null:value;
    } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException(); }
    catch(java.io.IOException error) { return null; }
    finally {if(process!=null&&process.isAlive()) process.destroyForcibly();}
  }
}
