package dev.mikoto2000.rei.launcher;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendConnectionProbeTest {
  @TempDir Path root;
  @Test void distinguishesAuthenticationCompatibilityForeignServiceAndStaleMetadata() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var response = new java.util.concurrent.atomic.AtomicReference<String>();
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    server.createContext("/api/v1/instance",exchange -> {
      assertEquals("Bearer test-secret",exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(),body.length);
      try (var out = exchange.getResponseBody()) { out.write(body); }
    });
    server.start();
    try (var client = HttpClient.newHttpClient()) {
      var identity = new BackendEndpoint(1,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
          ProcessHandle.current().pid(),"http://127.0.0.1:"+server.getAddress().getPort(),BackendEndpoint.CURRENT_API_PROTOCOL,"READY");
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      response.set(json.writeValueAsString(identity));
      var probe = new BackendConnectionProbe(client);
      assertEquals(BackendConnectionProbe.Status.KEY_MISSING,probe.check(identity,"",true).status());
      assertEquals(BackendConnectionProbe.Status.STALE,probe.check(identity,"test-secret",false).status());
      assertEquals(BackendConnectionProbe.Status.READY,probe.check(identity,"test-secret",true).status());
      status.set(401);
      assertEquals(BackendConnectionProbe.Status.AUTHENTICATION_FAILED,probe.check(identity,"test-secret",true).status());
      status.set(503);
      assertEquals(BackendConnectionProbe.Status.STARTING,probe.check(identity,"test-secret",true).status());
      status.set(200);
      response.set("{\"status\":\"ok\"}");
      assertEquals(BackendConnectionProbe.Status.IDENTITY_MISMATCH,probe.check(identity,"test-secret",true).status());
      response.set("x".repeat(16385));
      assertEquals(BackendConnectionProbe.Status.IDENTITY_MISMATCH,probe.check(identity,"test-secret",true).status());
      response.set(json.writeValueAsString(new BackendEndpoint(1,identity.instanceId(),identity.storageId(),identity.pid(),identity.baseUrl(),1,"READY")));
      assertEquals(BackendConnectionProbe.Status.INCOMPATIBLE,probe.check(identity,"test-secret",true).status());
      response.set(json.writeValueAsString(new BackendEndpoint(1,UUID.randomUUID().toString(),identity.storageId(),identity.pid(),identity.baseUrl(),BackendEndpoint.CURRENT_API_PROTOCOL,"READY")));
      assertEquals(BackendConnectionProbe.Status.IDENTITY_MISMATCH,probe.check(identity,"test-secret",true).status());
      server.stop(0);
      assertEquals(BackendConnectionProbe.Status.UNREACHABLE,probe.check(identity,"test-secret",true).status());
    } finally { server.stop(0); }
  }
}
