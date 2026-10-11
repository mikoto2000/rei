package dev.mikoto2000.rei.terminal;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.cli.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsPtyTest {
  @TempDir Path directory;
  @Test void interactiveShellReceivesInputInSelectedProjectAndReturnsToJline()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var root=directory.toRealPath();var json=BackendClient.JSON;
    server.createContext("/",exchange->{
      String text=exchange.getRequestURI().getPath().equals("/api/v1/cli/catalog")?"{\"commands\":[]}":exchange.getRequestURI().getPath().startsWith("/api/v1/sessions")?"{\"items\":[]}":
          json.writeValueAsString(exchange.getRequestMethod().equals("POST")?Map.of("id","fixture","name","Fixture","path",root.toString()):List.of(Map.of("id","fixture","name","Fixture","path",root.toString())));
      var bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    var argv=List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"--enable-native-access=ALL-UNNAMED","--add-opens=java.base/java.io=ALL-UNNAMED","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),Client.class.getName(),"http://127.0.0.1:"+server.getAddress().getPort(),root.toString());
    try(var pty=new WindowsPty(argv,root)) {
      pty.await("Connected.");pty.send("/sh\r");pty.await("/sh");
      var proof=root.resolve("shell-directory.txt");
      pty.send("Set-Content -LiteralPath '"+proof.toString().replace("'","''")+"' -Value ((Get-Location).Path)\r");
      long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
      String observed=null;
      while(System.nanoTime()<deadline) {
        try {if(Files.exists(proof))observed=Files.readString(proof).strip();}
        catch(java.nio.file.FileSystemException stillWriting) { /* PowerShell may still hold the newly created file. */ }
        if(root.toString().equals(observed))break;
        Thread.sleep(50);
      }
      assertEquals(root.toString(),observed,pty.output());
      int offset=pty.output().length();pty.send("exit\r");pty.awaitAfter("]> ",offset);
      pty.send("\u0003");Thread.sleep(100);offset=pty.output().length();pty.send("/help\r");pty.awaitAfter("/project",offset);
      pty.send("/exit\r");assertEquals(0,pty.exitCode());
    }finally{server.stop(0);}
  }
  public static final class Client {
    public static void main(String[] args)throws Exception {
      // Surefire itself has redirected handles. Bind the test client to its actual attached ConPTY.
      var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
      var input=kernel.CreateFile("CONIN$",0x80000000|0x40000000,3,null,3,0,null);
      var output=kernel.CreateFile("CONOUT$",0x80000000|0x40000000,3,null,3,0,null);
      if(!kernel.SetStdHandle(-10,input)||!kernel.SetStdHandle(-11,output)||!kernel.SetStdHandle(-12,output))throw new IOException("Cannot attach console handles");
      var handle=FileDescriptor.class.getDeclaredField("handle");handle.setAccessible(true);
      handle.setLong(FileDescriptor.in,com.sun.jna.Pointer.nativeValue(input.getPointer()));
      handle.setLong(FileDescriptor.out,com.sun.jna.Pointer.nativeValue(output.getPointer()));
      handle.setLong(FileDescriptor.err,com.sun.jna.Pointer.nativeValue(output.getPointer()));
      System.setIn(new FileInputStream("CONIN$"));
      System.setOut(new PrintStream(new FileOutputStream("CONOUT$"),true,java.nio.charset.StandardCharsets.UTF_8));System.setErr(System.out);
      try(var backend=new BackendClient(URI.create(args[0]),"fixture");var client=new TerminalClient(backend,Path.of(args[1]).resolve("history"),true)) {
        var field=TerminalClient.class.getDeclaredField("terminal");field.setAccessible(true);
        if(field.get(client) instanceof org.jline.terminal.impl.DumbTerminal)throw new IOException("PTY requires a native JLine terminal");
        System.out.println("PTY terminal: "+field.get(client).getClass().getName());
        client.selectInitialProject(Path.of(args[1]));client.run();
      }
    }
  }
}
