package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.core.process.RunCommandRequest;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import dev.mikoto2000.rei.httpapi.HttpApiProperties;
import dev.mikoto2000.rei.httpapi.HttpApiRequest;
import dev.mikoto2000.rei.httpapi.HttpApiService;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.json.JsonMapper;

/** Explicit local comparison; never calls an LLM or a remote service. */
@Tag("integration")
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "httpapi.benchmark", matches = "true")
class StructuredHttpBenchmarkTest {
  @Test
  void compareShellAndStructuredApiAgainstIdenticalFixture() throws Exception {
    var json = JsonMapper.builder().build();
    var items = json.createArrayNode();
    for (int i = 0; i < 100; i++)
      items.addObject().put("id", i).put("title", "task-" + i).put("detail", "x".repeat(200));
    byte[] payload = json.writeValueAsBytes(Map.of("items", items));
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tasks",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    server.createContext(
        "/error",
        exchange -> {
          byte[] body = "{\"error\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(400, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      var api =
          new HttpApiService(
              new SafeHttpFetcher(),
              new HttpApiProperties(List.of(URI.create(origin)), null, null, null, null));
      var tools =
          new Tools(
              null,
              new dev.mikoto2000.rei.core.service.SystemShellService() {
                @Override
                public String resolveShell(Map<String, String> env, String os) {
                  return "powershell";
                }
              },
              new dev.mikoto2000.rei.core.process.BackgroundProcessManager(
                  new dev.mikoto2000.rei.core.service.SystemShellService()));
      var report = new LinkedHashMap<String, Object>();
      report.put("timestamp", java.time.Instant.now().toString());
      report.put("java", System.getProperty("java.version"));
      report.put("os", System.getProperty("os.name"));
      report.put("fixtureResponseBytes", payload.length);
      report.put("llmCalls", 0);
      var request =
          new HttpApiRequest(origin + "/tasks", null, null, null, "/items", 5, null, null);
      long cold = System.nanoTime();
      assertNull(api.read(request).error());
      report.put("structuredColdMillis", (System.nanoTime() - cold) / 1_000_000.0);
      for (boolean extract : List.of(false, true)) {
        String command =
            "$ErrorActionPreference='Stop'; $r=Invoke-RestMethod -Uri '"
                + origin
                + "/tasks'; "
                + (extract ? "$r.items | Select-Object -First 5" : "$r")
                + " | ConvertTo-Json -Depth 20 -Compress";
        var shellInput = new RunCommandRequest(command, "foreground", 30);
        var apiInput =
            new HttpApiRequest(
                origin + "/tasks",
                null,
                null,
                null,
                extract ? "/items" : null,
                extract ? 5 : 100,
                null,
                null);
        List<Double> shellMillis = new ArrayList<>(), apiMillis = new ArrayList<>();
        int shellOutput = 0, apiOutput = 0;
        for (int i = 0; i < 5; i++) {
          long start = System.nanoTime();
          var shell =
              tools.runCommand(shellInput, Path.of(".").toAbsolutePath(), Duration.ofSeconds(30));
          shellMillis.add((System.nanoTime() - start) / 1_000_000.0);
          assertEquals(0, shell.exitCode());
          shellOutput = json.writeValueAsBytes(shell).length;
          start = System.nanoTime();
          var result = api.read(apiInput);
          apiMillis.add((System.nanoTime() - start) / 1_000_000.0);
          assertEquals(200, result.status());
          assertNull(result.error());
          apiOutput = json.writeValueAsBytes(result).length;
        }
        report.put(
            extract ? "extracted" : "full",
            Map.of(
                "samples",
                5,
                "shellSuccesses",
                5,
                "structuredSuccesses",
                5,
                "shellMillis",
                shellMillis,
                "structuredMillis",
                apiMillis,
                "shellToolOutputBytes",
                shellOutput,
                "structuredToolOutputBytes",
                apiOutput,
                "shellArgumentsBytes",
                json.writeValueAsBytes(Map.of("request", shellInput)).length,
                "structuredArgumentsBytes",
                json.writeValueAsBytes(Map.of("request", apiInput)).length));
      }
      var error =
          api.read(new HttpApiRequest(origin + "/error", null, null, null, null, null, null, null));
      assertEquals(400, error.status());
      assertEquals("HTTP_ERROR", error.error());
      report.put("structuredError", error);
      var shellError =
          tools.runCommand(
              new RunCommandRequest(
                  "$ErrorActionPreference='Stop'; Invoke-RestMethod -Uri '" + origin + "/error'",
                  "foreground",
                  30),
              Path.of(".").toAbsolutePath(),
              Duration.ofSeconds(30));
      report.put("shellError", shellError);
      Files.createDirectories(Path.of("target"));
      Files.writeString(
          Path.of("target/http-api-benchmark.json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    } finally {
      server.stop(0);
    }
  }
}
