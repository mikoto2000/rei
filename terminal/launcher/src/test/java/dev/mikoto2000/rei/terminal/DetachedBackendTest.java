package dev.mikoto2000.rei.terminal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

class DetachedBackendTest {
  @TempDir Path directory;
  @Test void quotesSpacesQuotesAndTrailingBackslashes() {
    assertEquals("\"C:\\directory name\\\\\"",DetachedBackend.quote("C:\\directory name\\"));
    assertEquals("\"a\\\"b\"",DetachedBackend.quote("a\"b"));
  }
  @Test void detachedWorkerSurvivesAbruptLauncherTermination()throws Exception {
    if(!System.getProperty("os.name").toLowerCase().contains("win"))return;
    Path marker=directory.resolve("heartbeat"),pidFile=directory.resolve("child.pid");
    String javaExecutable=Path.of(System.getProperty("java.home"),"bin/java.exe").toString();
    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Process parent=new ProcessBuilder(javaExecutable,"-cp",classpath,DetachedWorker.class.getName(),"parent",marker.toString(),pidFile.toString()).redirectError(directory.resolve("parent-error.log").toFile()).start();
    ProcessHandle child=null;
    try {
      long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(!Files.exists(pidFile)&&parent.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
      assertTrue(Files.exists(pidFile),()->"No detached child: "+read(directory.resolve("parent-error.log")));
      child=ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow();
      while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(50);
      assertTrue(Files.exists(marker));String before=Files.readString(marker);
      parent.destroyForcibly();assertTrue(parent.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));
      Thread.sleep(400);assertTrue(child.isAlive());assertNotEquals(before,Files.readString(marker));
    } finally{parent.destroyForcibly();if(child!=null)child.destroyForcibly();}
  }
  private static String read(Path file){try{return Files.readString(file);}catch(Exception ignored){return "unavailable";}}
}
