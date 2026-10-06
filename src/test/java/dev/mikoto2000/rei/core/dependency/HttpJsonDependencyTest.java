package dev.mikoto2000.rei.core.dependency;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class HttpJsonDependencyTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
  @Test void waitsForTypedScalarAtPointerAndDoesNotExposeResponse()throws Exception {
    var body=new AtomicReference<>("{\"jobs\":[{\"ready\":false}],\"secret\":\"private fixture\"}");
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/state",exchange->{byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      String url="http://127.0.0.1:"+server.getAddress().getPort()+"/state";
      String expected="{\"status\":200,\"pointer\":\"/jobs/0/ready\",\"value\":true}";
      assertEquals(DependencyState.WAITING,probe.probeJson("id",url,expected).state());
      body.set("{\"jobs\":[{\"ready\":true}],\"secret\":\"private fixture\"}");
      var result=probe.probeJson("id",url,expected);assertEquals(DependencyState.COMPLETED,result.state());
      assertEquals("http_json_value_verified",result.detail());assertFalse(result.toString().contains("private fixture"));
      body.set("{\"jobs\":[{\"ready\":\"true\"}]}");assertEquals(DependencyState.WAITING,probe.probeJson("id",url,expected).state());
    }finally{server.stop(0);}
  }
  @Test void validatesExplicitConditionAndOldPortCannotClaimVerification() {
    String expected="{\"status\":200,\"pointer\":\"/ready\",\"value\":true}";
    var spec=new DependencySpec(DependencySpec.Kind.HTTP_JSON_VALUE,"https://example.invalid",expected);assertTrue(spec.network());
    assertThrows(IllegalArgumentException.class,()->new DependencySpec(DependencySpec.Kind.HTTP_JSON_VALUE,spec.target(),"{\"status\":200,\"pointer\":\"/ready\",\"value\":{}}"));
    assertThrows(IllegalArgumentException.class,()->new DependencySpec(DependencySpec.Kind.HTTP_JSON_VALUE,spec.target(),"{\"status\":200,\"pointer\":\"/bad~2\",\"value\":true}"));
    DependencyHttpProbe old=(id,url,status)->new DependencyObservation(id,DependencyState.COMPLETED,"http_status_verified");
    assertEquals(DependencyState.BLOCKED,old.probeJson("id",spec.target(),expected).state());
  }
  @Test void scalarComparisonPreservesDecimalPrecisionNullAndPointerEscapes()throws Exception {
    var number=HttpJsonCondition.parse("{\"status\":200,\"pointer\":\"/value\",\"value\":0.1}");
    assertFalse(number.matches("{\"value\":0.10000000000000001}".getBytes(StandardCharsets.UTF_8)));
    assertFalse(number.matches("{\"value\":1e309}".getBytes(StandardCharsets.UTF_8)));
    assertFalse(number.matches("{\"value\":\"0.1\"}".getBytes(StandardCharsets.UTF_8)));
    assertTrue(number.matches("{\"value\":0.10}".getBytes(StandardCharsets.UTF_8)));
    var nil=HttpJsonCondition.parse("{\"status\":200,\"pointer\":\"/a~1b/~0\",\"value\":null}");
    assertTrue(nil.matches("{\"a/b\":{\"~\":null}}".getBytes(StandardCharsets.UTF_8)));
    assertFalse(nil.matches("{\"a/b\":{}}".getBytes(StandardCharsets.UTF_8)));
  }
  @Test void malformedDuplicateDeepOversizedBodiesAndWrongStatusNeverComplete()throws Exception {
    var body=new AtomicReference<>("{\"ready\":true,\"ready\":false}");
    var redirected=new java.util.concurrent.atomic.AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/state",exchange->{byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,0);try{exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
    server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
    server.createContext("/target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
    server.createContext("/partial",exchange->{exchange.sendResponseHeaders(200,100);try{exchange.getResponseBody().write("{\"ready\":true}".getBytes(StandardCharsets.UTF_8));}finally{exchange.close();}});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      String url="http://127.0.0.1:"+server.getAddress().getPort()+"/state",expected="{\"status\":200,\"pointer\":\"/ready\",\"value\":true}";
      for(String invalid:java.util.List.of("{\"ready\":true,\"ready\":false}","{\"ready\":true} {}","{bad}","[".repeat(34)+"0"+"]".repeat(34))) {
        body.set(invalid);var result=probe.probeJson("id",url,expected);assertEquals(DependencyState.WAITING,result.state());assertEquals("http_json_invalid",result.detail());
      }
      String prefix="{\"ready\":true,\"padding\":\"",suffix="\"}";
      String exact=prefix+"x".repeat(65536-prefix.length()-suffix.length())+suffix;body.set(exact);
      assertEquals(DependencyState.COMPLETED,probe.probeJson("id",url,expected).state());
      body.set(exact+" ");assertEquals("http_body_too_large",probe.probeJson("id",url,expected).detail());
      body.set("{\"ready\":true}");assertEquals("http_status_mismatch",probe.probeJson("id",url,expected.replace("200","202")).detail());
      String base=url.substring(0,url.lastIndexOf('/'));
      assertEquals("http_status_mismatch",probe.probeJson("id",base+"/redirect",expected).detail());assertEquals(0,redirected.get());
      assertEquals("http_unavailable",probe.probeJson("id",base+"/partial",expected).detail());
    }finally{server.stop(0);}
    for(String invalid:java.util.List.of("{\"status\":200,\"status\":201,\"pointer\":\"/x\",\"value\":true}","{\"status\":200,\"pointer\":\"\",\"value\":true}","{\"status\":200,\"pointer\":\"/x\",\"value\":true,\"code\":\"run\"}","{\"status\":600,\"pointer\":\"/x\",\"value\":true}"))
      assertThrows(IllegalArgumentException.class,()->HttpJsonCondition.parse(invalid));
  }
  @Test void persistsConditionAndHonorsNetworkToolsAndAutomaticPermission()throws Exception {
    var requests=new java.util.concurrent.atomic.AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/ready",exchange->{requests.incrementAndGet();byte[] bytes="{\"ready\":true,\"private\":\"private fixture\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var http=new JavaHttpDependencyProbe()) {
      var clock=java.time.Clock.fixed(java.time.Instant.EPOCH,java.time.ZoneOffset.UTC);var data=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("deps.db"));
      var repo=new PersistentDependencyRepository(data,clock);String project=java.util.UUID.randomUUID().toString();
      var source=new DependencySourceProbe(org.mockito.Mockito.mock(dev.mikoto2000.rei.goal.FileGoalVerifier.class),org.mockito.Mockito.mock(dev.mikoto2000.rei.workcontext.WorkContextGit.class),org.mockito.Mockito.mock(dev.mikoto2000.rei.core.process.BackgroundProcessManager.class),http,clock);
      var read=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null));
      var service=new DependencyObservationService(repo,source,new DependencyWatcherProperties(true),read,e->{});var tools=new DependencyTools(repo,service,source);
      try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("r","s",root,project))) {
        String expected="{\"status\":200,\"pointer\":\"/ready\",\"value\":true}";
        var entry=tools.registerDependency("HTTP_JSON_VALUE","http://127.0.0.1:"+server.getAddress().getPort()+"/ready",expected,null,null);
        assertEquals(entry.spec(),new PersistentDependencyRepository(data,clock).get(project,entry.id()).spec());
        assertThrows(IllegalArgumentException.class,()->tools.checkDependency(entry.id()));assertThrows(IllegalArgumentException.class,()->tools.waitForDependency(entry.id(),0,null));
        service.tick();assertEquals(0,requests.get());assertEquals("permission_required",repo.get(project,entry.id()).reason());
        assertEquals(DependencyState.COMPLETED,tools.checkHttpDependency(entry.id()).state());assertEquals(1,requests.get());
        assertFalse(repo.history(project,entry.id()).toString().contains("private fixture"));
        assertEquals(DependencyState.COMPLETED,tools.waitForHttpDependency(entry.id(),0,null).state());assertEquals(1,requests.get());
        var next=tools.registerDependency("HTTP_JSON_VALUE",entry.spec().target(),expected,null,null);
        var allowed=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,java.util.Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ,dev.mikoto2000.rei.core.policy.ActionCapability.NETWORK_READ),null,null));
        new DependencyObservationService(repo,source,new DependencyWatcherProperties(true),allowed,e->{}).tick();assertEquals(DependencyState.COMPLETED,repo.get(project,next.id()).state());assertEquals(2,requests.get());
      }
    }finally{server.stop(0);}
  }
  @Test void interruptedJsonRequestPropagatesCancellation()throws Exception {
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/slow",exchange->{try{exchange.sendResponseHeaders(200,0);entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->probe.probeJson("id","http://127.0.0.1:1","{\"status\":200,\"pointer\":\"/ready\",\"value\":true}"));}finally{Thread.interrupted();}
      var result=new java.util.concurrent.CompletableFuture<Boolean>();String url="http://127.0.0.1:"+server.getAddress().getPort()+"/slow";
      var worker=Thread.ofPlatform().start(()->{try{probe.probeJson("id",url,"{\"status\":200,\"pointer\":\"/ready\",\"value\":true}");result.complete(false);}catch(java.util.concurrent.CancellationException error){result.complete(Thread.currentThread().isInterrupted());}catch(Throwable error){result.completeExceptionally(error);}});
      try{assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));worker.interrupt();assertTrue(result.get(2,java.util.concurrent.TimeUnit.SECONDS));}
      finally{release.countDown();worker.interrupt();worker.join(3000);}
    }finally{release.countDown();server.stop(0);}
  }
}
