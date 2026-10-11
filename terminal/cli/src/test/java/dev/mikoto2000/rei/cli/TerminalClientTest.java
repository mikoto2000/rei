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
  @Test void commandTokenizationOmitsEmptyQuotedArgumentsLikeExistingShell() {
    assertEquals(java.util.List.of("/models"),TerminalClient.commandWords("/models \"\" "));
    assertEquals(java.util.List.of("/chat","hello world","C:\\work\\file"),TerminalClient.commandWords("/chat \"hello world\" C:\\work\\file"));
  }
  @Test void coachingUsesTheReadRevisionAndDoesNotWriteBeforeReading()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new java.util.ArrayList<String>();var written=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    server.createContext("/api/v1/activity",exchange->{String response;if(exchange.getRequestMethod().equals("GET")){calls.add("read");response="{\"revision\":7}";}else{calls.add("write");written.set(BackendClient.JSON.readTree(exchange.getRequestBody()));response="{\"text\":\"enabled\"}";}var bytes=response.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build())){client.command("/activity coaching on");assertEquals(java.util.List.of("read","write"),calls);assertEquals(7,written.get().path("expectedRevision").asLong());assertTrue(written.get().path("enabled").asBoolean());}finally{server.stop(0);}
  }
  @Test void modelControlsUseShellTextAndCacheNamesForCompletion()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var output=new ByteArrayOutputStream();
    server.createContext("/api/v1/models",exchange->{
      String response=exchange.getRequestURI().getPath().endsWith("/current")?"{\"model\":\"chosen\"}":"{\"models\":[\"chosen\",\"other\"],\"currentModel\":\"chosen\"}";
      var bytes=response.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");
        var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),output).build())) {
      client.command("/models");assertTrue(output.toString().contains("* chosen"));assertFalse(output.toString().contains("currentModel"));
      assertEquals(java.util.List.of("other"),client.completionValues(java.util.List.of("/model","o"),1,"o"));
      output.reset();client.command("/model chosen");assertTrue(output.toString().contains("current model: chosen"));
    }finally{server.stop(0);}
  }
  @Test void startupWithoutProjectOptionSelectsTheClientWorkingDirectory()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var registered=new java.util.concurrent.atomic.AtomicReference<String>();
    String path=BackendClient.JSON.writeValueAsString(Path.of("").toAbsolutePath().normalize().toString());
    server.createContext("/api/v1/projects",exchange->{
      String response;if(exchange.getRequestMethod().equals("POST")){registered.set(BackendClient.JSON.readTree(exchange.getRequestBody()).path("path").asText());response="{\"id\":\"cwd\",\"name\":\"Current\",\"path\":"+path+"}";}else response="[{\"id\":\"cwd\",\"name\":\"Current\",\"path\":"+path+"}]";
      var bytes=response.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.createContext("/api/v1/sessions",exchange->{var bytes="{\"items\":[]}".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");
        var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build())) {
      client.selectInitialProject(null);assertEquals("cwd",client.projectId());assertEquals(Path.of("").toAbsolutePath().normalize().toString(),registered.get());
    }finally{server.stop(0);}
  }
  @Test void tabCompletionUsesTheCatalogCacheWithoutAnyAdditionalHttpRequest()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new java.util.concurrent.atomic.AtomicInteger();
    var command=new dev.mikoto2000.rei.application.cli.CliCatalog.Command("session",java.util.List.of(),"Sessions",java.util.List.of(),java.util.List.of(),java.util.List.of(new dev.mikoto2000.rei.application.cli.CliCatalog.Command("switch",java.util.List.of("resume"),"Select",java.util.List.of(),java.util.List.of(),java.util.List.of())));
    var bytes=BackendClient.JSON.writeValueAsBytes(new dev.mikoto2000.rei.application.cli.CliCatalog(java.util.List.of(command)));
    server.createContext("/api/v1/cli/catalog",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");
        var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build())) {
      client.refreshCatalog();assertEquals(1,requests.get());
      for(int index=0;index<100;index++)assertEquals(java.util.List.of("resume"),client.completionValues(java.util.List.of("/session","r"),1,"r"));
      client.refreshCatalog();assertEquals(1,requests.get());
    }finally{server.stop(0);}
  }
  @Test void sessionListReadsEveryPageAndUsesTheShellDisplay()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var pages=new java.util.concurrent.atomic.AtomicInteger();var output=new ByteArrayOutputStream();
    server.createContext("/api/v1/projects",exchange->{var bytes="[{\"id\":\"one\",\"name\":\"One\"}]".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
    server.createContext("/api/v1/sessions",exchange->{
      String query=exchange.getRequestURI().getQuery();String response="{\"items\":[]}";
      if(query!=null&&query.contains("limit=100")){pages.incrementAndGet();response=query.contains("cursor=next")?"{\"items\":[{\"sessionId\":\"second\",\"title\":\"Second\"}]}":"{\"items\":[{\"sessionId\":\"first\",\"title\":\"First\"}],\"nextCursor\":\"next\"}";}
      var bytes=response.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");
        var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),output).build())) {
      client.command("/project select one");pages.set(0);client.command("/session list");
      assertEquals(2,pages.get());assertTrue(output.toString().contains("Sessions:"));assertTrue(output.toString().contains("first  First"));assertTrue(output.toString().contains("second  Second"));
      assertThrows(IllegalArgumentException.class,()->client.command("/session list extra"));
    }finally{server.stop(0);}
  }
  @Test void changingToTheSameProjectPreservesTheClientSession()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    String path=BackendClient.JSON.writeValueAsString(directory.toString());
    server.createContext("/api/v1/projects",exchange->{
      var response=exchange.getRequestMethod().equals("POST")?"{\"id\":\"one\",\"name\":\"One\",\"path\":"+path+"}":"[{\"id\":\"one\",\"name\":\"One\",\"path\":"+path+"}]";
      var bytes=response.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.createContext("/api/v1/sessions",exchange->{
      var response=exchange.getRequestURI().getPath().endsWith("/chosen")?"{\"sessionId\":\"chosen\",\"projectId\":\"one\"}":"{\"items\":[{\"sessionId\":\"latest\"}]}";
      var bytes=response.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");
        var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build())) {
      client.command("/project select one");client.command("/session select chosen");client.command("/project cd .");
      assertEquals("chosen",client.sessionId());
    }finally{server.stop(0);}
  }
  @Test void projectAddDoesNotChangeSelectionAndCdUsesSelectedDirectory()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var registrations=new java.util.ArrayList<String>();
    server.createContext("/api/v1/projects",exchange->{
      String response;
      if(exchange.getRequestMethod().equals("POST")) {
        var body=BackendClient.JSON.readTree(exchange.getRequestBody());registrations.add(body.path("path").asText());
        response="{\"id\":\"two\",\"name\":\"Two\",\"path\":"+BackendClient.JSON.writeValueAsString(directory.resolve("child").toString())+"}";
      } else response="[{\"id\":\"one\",\"name\":\"One\",\"path\":"+BackendClient.JSON.writeValueAsString(directory.toString())+"},{\"id\":\"two\",\"name\":\"Two\",\"path\":"+BackendClient.JSON.writeValueAsString(directory.resolve("child").toString())+"}]";
      var bytes=response.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.createContext("/api/v1/sessions",exchange->{var bytes="{\"items\":[]}".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var backend=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture");
        var client=new TerminalClient(backend,directory,true,TerminalBuilder.builder().system(false).dumb(true).streams(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()).build())) {
      client.command("/project select one");client.command("/project add child");assertEquals("one",client.projectId());
      client.command("/project cd child");assertEquals("two",client.projectId());
      assertEquals(java.util.List.of(directory.resolve("child").toString(),directory.resolve("child").toString()),registrations);
      assertThrows(IllegalArgumentException.class,()->client.command("/project add child extra"));
    }finally{server.stop(0);}
  }
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
