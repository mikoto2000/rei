package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

class BackendClientTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"","not-json","{\"message\":\"Bearer test-key\"}"})
  void unstructuredConflictBodiesAreNotExposed(String body)throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/projects",exchange->{var bytes=body.getBytes();exchange.sendResponseHeaders(409,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var client=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-key")) {
      var error=assertThrows(BackendClient.ApiException.class,()->client.get("/api/v1/projects"));assertEquals(409,error.status());assertFalse(error.getMessage().contains("test-key"));
    }finally{server.stop(0);}
  }
  @Test void sessionBusyShowsItsTargetRunWithoutResubmissionOrReceiptLookup()throws Exception {
    var requests=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/chat",exchange->{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();var body="{\"projectId\":\"project\",\"sessionId\":\"project:project:chat:session\",\"runId\":\"busy-run\"}".getBytes();exchange.sendResponseHeaders(409,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    try(var client=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-key")) {
      var error=assertThrows(BackendClient.ApiException.class,()->client.submit("project","session","hello","receipt"));
      assertTrue(error.getMessage().contains("busy-run"));assertEquals(1,requests.get());
    }finally{server.stop(0);}
  }
  @Test void detachWhileWaitingForHeadersCannotAttachAnOldSubscription()throws Exception {
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var events=new AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/runs/run/events",exchange->{entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}var body="id: 1\nevent: message.delta\ndata: {}\n\n".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    try(var client=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-key");var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var pending=executor.submit(()->client.events("run",0,event->events.incrementAndGet()));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));client.detach();release.countDown();pending.get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(0,events.get());
    }finally{release.countDown();server.stop(0);}
  }
  @Test void lostAcceptanceResponseLooksUpReceiptWithoutPostingAgain()throws Exception {
    var posts=new AtomicInteger();var receipts=new AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/chat",exchange->{
      assertEquals("Bearer test-key",exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getRequestBody().readAllBytes();
      if(exchange.getRequestMethod().equals("POST")){posts.incrementAndGet();exchange.close();}
      else {receipts.incrementAndGet();var body="{\"runId\":\"run\",\"sessionId\":\"session\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();}
    });server.start();
    try(var client=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-key")) {
      assertEquals("run",client.submit("project",null,"hello","key").path("runId").asText());
      assertEquals(1,posts.get());assertEquals(1,receipts.get());
    } finally{server.stop(0);}
  }
  @Test void missingReceiptAfterLostResponseRemainsUncertain()throws Exception {
    var posts=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/chat",exchange->{exchange.getRequestBody().readAllBytes();if(exchange.getRequestMethod().equals("POST")){posts.incrementAndGet();exchange.close();}else{exchange.sendResponseHeaders(404,-1);exchange.close();}});server.start();
    try(var client=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-key")) {
      assertThrows(BackendClient.UncertainAcceptanceException.class,()->client.submit("p",null,"message","key"));assertEquals(1,posts.get());
    } finally{server.stop(0);}
  }
  @Test void serverFailureAfterAcceptanceAlsoUsesReceiptWithoutResubmitting()throws Exception {
    var posts=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/v1/chat",exchange->{exchange.getRequestBody().readAllBytes();if(exchange.getRequestMethod().equals("POST")){posts.incrementAndGet();exchange.sendResponseHeaders(500,-1);}else{var body="{\"runId\":\"run\",\"sessionId\":\"session\",\"status\":\"FAILED\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}exchange.close();});server.start();
    try(var client=new BackendClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-key")) {
      assertEquals("FAILED",client.submit("p",null,"message","key").path("status").asText());assertEquals(1,posts.get());
    }finally{server.stop(0);}
  }
}
