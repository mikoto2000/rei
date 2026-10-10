package dev.mikoto2000.rei.core.dependency;

import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DoctorConnectivityProbeTest {
  @Test void httpResponseReportsOnlyStatusWithoutBodyOrCredentials() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/models", exchange -> {exchange.sendResponseHeaders(401, 0); try(var body=exchange.getResponseBody()){body.write("token=BODY-SECRET".getBytes());}});
    server.start();
    try(var probe = new JavaHttpDependencyProbe()) {
      var result = probe.connectivity("http://127.0.0.1:"+server.getAddress().getPort()+"/models", Duration.ofSeconds(2), () -> {});
      assertEquals(JavaHttpDependencyProbe.ConnectivityReason.RESPONSE, result.reason()); assertEquals(401, result.httpStatus());
      assertFalse(result.toString().contains("BODY-SECRET"));
      assertThrows(IllegalArgumentException.class, () -> probe.connectivity("http://user:SECRET@127.0.0.1/models", Duration.ofSeconds(1), () -> {}));
    } finally {server.stop(0);}
  }
  @Test void refusedConnectionAndTimeoutRemainDistinct() throws Exception {
    int unused; try(var port=new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))){unused=port.getLocalPort();}
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
    server.createContext("/slow", exchange -> {try {Thread.sleep(1000); exchange.sendResponseHeaders(200,-1);}catch(Exception ignored){}finally{exchange.close();}}); server.start();
    try(var probe = new JavaHttpDependencyProbe()) {
      var refused = probe.connectivity("http://127.0.0.1:"+unused, Duration.ofSeconds(2), () -> {});
      assertEquals(JavaHttpDependencyProbe.ConnectivityReason.CONNECTION_FAILED, refused.reason());
      var timed = probe.connectivity("http://127.0.0.1:"+server.getAddress().getPort()+"/slow", Duration.ofMillis(100), () -> {});
      assertEquals(JavaHttpDependencyProbe.ConnectivityReason.TIMEOUT, timed.reason());
    } finally {server.stop(0); executor.shutdownNow();}
  }
  @Test void cancellationStopsPendingObservation() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
    server.createContext("/slow", exchange -> {try{Thread.sleep(1000);}catch(Exception ignored){}finally{exchange.close();}});server.start();
    var checks=new java.util.concurrent.atomic.AtomicInteger();
    try(var probe = new JavaHttpDependencyProbe()) {
      assertThrows(CancellationException.class, () -> probe.connectivity("http://127.0.0.1:"+server.getAddress().getPort()+"/slow", Duration.ofSeconds(2), () -> {if(checks.incrementAndGet()>2) throw new CancellationException();}));
      assertTrue(checks.get()>2);
    } finally {server.stop(0);executor.shutdownNow();}
  }
}
