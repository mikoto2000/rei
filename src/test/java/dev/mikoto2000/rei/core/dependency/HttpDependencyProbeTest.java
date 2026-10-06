package dev.mikoto2000.rei.core.dependency;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import com.sun.net.httpserver.HttpServer;
@Tag("integration")
class HttpDependencyProbeTest {
  @Test void realHttpStatusTransitionAndRedirectRefusal() throws Exception {
    var status=new AtomicInteger(202);var redirected=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/state",exchange->{exchange.sendResponseHeaders(status.get(),-1);exchange.close();});
    server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
    server.createContext("/target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      String base="http://127.0.0.1:"+server.getAddress().getPort();
      assertEquals(DependencyState.WAITING,probe.probe("id",base+"/state",200).state());status.set(200);
      assertEquals(DependencyState.COMPLETED,probe.probe("id",base+"/state",200).state());
      assertEquals(DependencyState.WAITING,probe.probe("id",base+"/redirect",200).state());assertEquals(0,redirected.get());
      assertThrows(IllegalArgumentException.class,()->probe.probe("id","file:///private",200));
    }finally {server.stop(0);}
  }
  @Test void interruptionAbortsObservationWithoutClaimingSuccess() {
    try(var probe=new JavaHttpDependencyProbe()) {
      Thread.currentThread().interrupt();try {assertThrows(java.util.concurrent.CancellationException.class,()->probe.probe("id","http://127.0.0.1:1",200));}finally {Thread.interrupted();}
    }
  }
}
