package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.terminal.TerminalBuilder;
import java.nio.file.Path;
import java.net.*;
import java.io.*;
import com.sun.net.httpserver.HttpServer;

class TerminalClientTest {
  @TempDir Path directory;
  @Test void quotedWindowsPathsRetainBackslashes() {
    assertEquals(java.util.List.of("/project","register","C:\\project with spaces\\rei"),TerminalClient.commandWords("/project register \"C:\\project with spaces\\rei\""));
  }
  @Test void unknownSlashCommandsNeverSendAChatRequest()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new java.util.concurrent.atomic.AtomicInteger();
    server.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");
        var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build())) {
      assertThrows(IllegalArgumentException.class,()->client.command("/unsupported do something"));assertEquals(0,requests.get());
    }finally{server.stop(0);}
  }
  @Test void twoClientsKeepProjectSelectionsSeparate()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/projects",exchange->{var bytes="[{\"id\":\"one\",\"name\":\"One\"},{\"id\":\"two\",\"name\":\"Two\"}]".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
    server.createContext("/api/v1/sessions",exchange->{var bytes="{\"items\":[]}".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    try(var firstBackend=new BackendClient(endpoint,"fixture");var secondBackend=new BackendClient(endpoint,"fixture");
        var first=new TerminalClient(firstBackend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build());
        var second=new TerminalClient(secondBackend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build())) {
      first.command("/project select one");second.command("/project select two");assertEquals("one",first.projectId());assertEquals("two",second.projectId());
      assertThrows(IllegalArgumentException.class,()->first.command("/project select missing"));assertEquals("one",first.projectId());
    }finally{server.stop(0);}
  }
}
