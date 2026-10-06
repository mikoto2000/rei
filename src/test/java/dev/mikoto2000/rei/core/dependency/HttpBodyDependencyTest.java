package dev.mikoto2000.rei.core.dependency;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class HttpBodyDependencyTest {
  @TempDir Path root;
  private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  @Test void verifiesStatusAndCompleteBodyDigestWithoutExposingBody()throws Exception {
    var body=new AtomicReference<>("ready 日本語".getBytes(StandardCharsets.UTF_8));
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/state",exchange->{byte[] bytes=body.get();exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      String url="http://127.0.0.1:"+server.getAddress().getPort()+"/state";String expected=digest(body.get());
      var result=probe.probeBody("id",url,200,expected);
      assertEquals(DependencyState.COMPLETED,result.state());assertEquals("http_body_digest_verified",result.detail());
      body.set("not ready secret fixture".getBytes(StandardCharsets.UTF_8));
      result=probe.probeBody("id",url,200,expected);assertEquals(DependencyState.WAITING,result.state());
      assertEquals("http_body_digest_mismatch",result.detail());assertFalse(result.toString().contains("secret fixture"));
      assertEquals("http_status_mismatch",probe.probeBody("id",url,202,expected).detail());
    }finally{server.stop(0);}
  }
  @Test void validatesPersistableExpectationAndLegacyPortCannotClaimBodyVerification() {
    var spec=new DependencySpec(DependencySpec.Kind.HTTP_BODY_SHA256,"https://example.invalid/ready","200:"+"A".repeat(64));
    assertEquals("200:"+"a".repeat(64),spec.expected());assertTrue(spec.network());
    assertThrows(IllegalArgumentException.class,()->new DependencySpec(DependencySpec.Kind.HTTP_BODY_SHA256,"https://example.invalid","200:bad"));
    assertThrows(IllegalArgumentException.class,()->new DependencySpec(DependencySpec.Kind.HTTP_BODY_SHA256,"https://user:pass@example.invalid","200:"+"a".repeat(64)));
    DependencyHttpProbe legacy=(id,url,status)->new DependencyObservation(id,DependencyState.COMPLETED,"http_status_verified");
    assertEquals(DependencyState.BLOCKED,legacy.probeBody("id",spec.target(),200,"a".repeat(64)).state());
    assertThrows(IllegalArgumentException.class,()->new DependencySpec(DependencySpec.Kind.HTTP_BODY_SHA256,"https://example.invalid","600:"+"a".repeat(64)));
    assertThrows(IllegalArgumentException.class,()->new DependencySpec(DependencySpec.Kind.HTTP_BODY_SHA256,"file:///private","200:"+"a".repeat(64)));
    assertThrows(IllegalArgumentException.class,()->new DependencySpec(DependencySpec.Kind.HTTP_BODY_SHA256,"https://example.invalid/#fragment","200:"+"a".repeat(64)));
  }
  @Test void exactByteLimitAndEmptyBodyAreVerifiedButOverflowAndRedirectAreRejected()throws Exception {
    var body=new AtomicReference<>(new byte[65536]);var redirected=new AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/body",exchange->{byte[] bytes=body.get();exchange.sendResponseHeaders(200,0);try{exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
    server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
    server.createContext("/target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      String base="http://127.0.0.1:"+server.getAddress().getPort();String expected=digest(body.get());
      assertEquals(DependencyState.COMPLETED,probe.probeBody("id",base+"/body",200,expected).state());
      body.set(new byte[65537]);var large=probe.probeBody("id",base+"/body",200,expected);
      assertEquals(DependencyState.BLOCKED,large.state());assertEquals("http_body_too_large",large.detail());
      body.set(new byte[0]);assertEquals(DependencyState.COMPLETED,probe.probeBody("id",base+"/body",200,digest(body.get())).state());
      assertEquals(DependencyState.WAITING,probe.probeBody("id",base+"/redirect",200,digest(body.get())).state());assertEquals(0,redirected.get());
    }finally{server.stop(0);}
  }
  @Test void incompleteBodyDoesNotCompleteAndInterruptedCallPropagatesCancellation()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/partial",exchange->{exchange.sendResponseHeaders(200,10);try{exchange.getResponseBody().write(new byte[3]);}finally{exchange.close();}});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      String url="http://127.0.0.1:"+server.getAddress().getPort()+"/partial";
      var result=probe.probeBody("id",url,200,digest(new byte[3]));assertEquals(DependencyState.WAITING,result.state());assertEquals("http_unavailable",result.detail());
      Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->probe.probeBody("id",url,200,"a".repeat(64)));}finally{Thread.interrupted();}
    }finally{server.stop(0);}
  }
  @Test void sqliteRestartToolsAndWatcherKeepNetworkAuthorizationAndDoNotStoreBody()throws Exception {
    byte[] body="private response fixture".getBytes(StandardCharsets.UTF_8);var requests=new AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/ready",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    try(var http=new JavaHttpDependencyProbe()) {
      var clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC);var datasource=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("body.db"));
      var repo=new PersistentDependencyRepository(datasource,clock);String project=UUID.randomUUID().toString();
      var source=new DependencySourceProbe(org.mockito.Mockito.mock(dev.mikoto2000.rei.goal.FileGoalVerifier.class),org.mockito.Mockito.mock(dev.mikoto2000.rei.workcontext.WorkContextGit.class),org.mockito.Mockito.mock(dev.mikoto2000.rei.core.process.BackgroundProcessManager.class),http,clock);
      var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null));
      var service=new DependencyObservationService(repo,source,new DependencyWatcherProperties(true),policy,e->{});var tools=new DependencyTools(repo,service,source);
      try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("r","s",root,project))) {
        var entry=tools.registerDependency("HTTP_BODY_SHA256","http://127.0.0.1:"+server.getAddress().getPort()+"/ready","200:"+digest(body),null,null);
        var reopened=new PersistentDependencyRepository(datasource,clock);assertEquals(entry.spec(),reopened.get(project,entry.id()).spec());
        assertThrows(IllegalArgumentException.class,()->tools.checkDependency(entry.id()));
        assertThrows(IllegalArgumentException.class,()->tools.waitForDependency(entry.id(),0,null));
        service.tick();assertEquals(0,requests.get());assertEquals("permission_required",repo.get(project,entry.id()).reason());
        assertEquals(DependencyState.COMPLETED,tools.checkHttpDependency(entry.id()).state());assertEquals(1,requests.get());
        assertFalse(repo.history(project,entry.id()).toString().contains("private response fixture"));
        assertEquals(DependencyState.COMPLETED,tools.waitForHttpDependency(entry.id(),0,null).state());assertEquals(1,requests.get());
        var automatic=tools.registerDependency("HTTP_BODY_SHA256",entry.spec().target(),entry.spec().expected(),null,null);
        var allowed=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ,dev.mikoto2000.rei.core.policy.ActionCapability.NETWORK_READ),null,null));
        new DependencyObservationService(repo,source,new DependencyWatcherProperties(true),allowed,e->{}).tick();
        assertEquals(DependencyState.COMPLETED,repo.get(project,automatic.id()).state());assertEquals(2,requests.get());
      }
    }finally{server.stop(0);}
  }
  @Test void cancellationDuringBodyReadCancelsOwnedRequestAndPreservesInterrupt()throws Exception {
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/slow",exchange->{try{exchange.sendResponseHeaders(200,0);entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      var result=new java.util.concurrent.CompletableFuture<Boolean>();
      String url="http://127.0.0.1:"+server.getAddress().getPort()+"/slow";
      var worker=Thread.ofPlatform().start(()->{try{probe.probeBody("id",url,200,"a".repeat(64));result.complete(false);}catch(java.util.concurrent.CancellationException error){result.complete(Thread.currentThread().isInterrupted());}catch(Throwable error){result.completeExceptionally(error);}});
      try{assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));worker.interrupt();assertTrue(result.get(2,java.util.concurrent.TimeUnit.SECONDS));}
      finally{release.countDown();worker.interrupt();worker.join(3000);}
    }finally{release.countDown();server.stop(0);}
  }
}
