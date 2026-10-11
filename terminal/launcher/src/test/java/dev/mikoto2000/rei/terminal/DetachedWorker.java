package dev.mikoto2000.rei.terminal;
import java.nio.file.*;
import java.util.*;
public final class DetachedWorker {
  public static void main(String[] args)throws Exception {
    if(args[0].equals("child")) {for(int i=0;i<300;i++){Files.writeString(Path.of(args[1]),Integer.toString(i));Thread.sleep(100);}return;}
    String java=Path.of(System.getProperty("java.home"),"bin/java.exe").toString();
    long pid=DetachedBackend.start(List.of(java,"-cp",System.getProperty("java.class.path"),DetachedWorker.class.getName(),"child",args[1]),Path.of(args[1]).getParent(),Path.of(args[1]).resolveSibling("child.log"));
    Files.writeString(Path.of(args[2]),Long.toString(pid));System.in.read();
  }
}
