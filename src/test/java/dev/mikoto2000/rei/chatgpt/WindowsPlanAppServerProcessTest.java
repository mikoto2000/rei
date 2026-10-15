package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** No login/Turn: real native launcher and MXC filesystem helper, opt-in locally. */
@EnabledOnOs(OS.WINDOWS)
class WindowsPlanAppServerProcessTest {
  @TempDir Path root;

  @Test
  @EnabledIfEnvironmentVariable(named = "REI_TEST_WINDOWS_CODEX", matches = "(?i)[A-Z]:.+")
  void nativeProcessHasDeadlineEvenWhenNoRpcIsSent() throws Exception {
    var launcher =
        new WindowsPlanAppServerLauncher(
            new PlanAppServerProperties(
                true, root, null, System.getenv("REI_TEST_WINDOWS_CODEX"), "windows"));
    var token =
        new TokenSet(
            "fixture",
            "fixture",
            "fixture",
            null,
            "dummy-no-inference",
            null,
            Set.of(),
            Instant.now().plusSeconds(60).getEpochSecond());
    var started = launcher.start(token, Duration.ofSeconds(1), () -> false);
    long pid = ((AppServerProcessSession) started.session()).pid();
    try (var session = started.session()) {
      ProcessHandle.of(pid).orElseThrow().onExit().get(4, java.util.concurrent.TimeUnit.SECONDS);
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    try (var homes = Files.list(root)) {
      assertEquals(0, homes.count());
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "REI_TEST_WINDOWS_CODEX", matches = "(?i)[A-Z]:.+")
  void startsNativeRestrictedThreadThenDeletesOwnHome() throws Exception {
    var launcher =
        new WindowsPlanAppServerLauncher(
            new PlanAppServerProperties(
                true, root, null, System.getenv("REI_TEST_WINDOWS_CODEX"), "windows"));
    var token =
        new TokenSet(
            "fixture",
            "fixture",
            "fixture",
            null,
            "dummy-no-inference",
            null,
            Set.of(),
            Instant.now().plusSeconds(60).getEpochSecond());
    var started = launcher.start(token, Duration.ofSeconds(20), () -> false);
    var json = new ObjectMapper();
    long ownedPid = ((AppServerProcessSession) started.session()).pid();
    try (var session = started.session()) {
      session.send(
          json.writeValueAsString(
              Map.of(
                  "id",
                  1,
                  "method",
                  "initialize",
                  "params",
                  Map.of(
                      "clientInfo",
                      Map.of("name", "rei", "version", "1.0"),
                      "capabilities",
                      Map.of("experimentalApi", true)))));
      var init = response(session, 1, json);
      assertTrue(init.path("userAgent").asText().startsWith("rei/0.162.1 "));
      assertEquals("windows", init.path("platformOs").asText());
      session.send("{\"method\":\"initialized\",\"params\":{}}");
      session.send(
          json.writeValueAsString(
              Map.of(
                  "id",
                  2,
                  "method",
                  "thread/start",
                  "params",
                  Map.of(
                      "cwd",
                      started.cwd(),
                      "ephemeral",
                      true,
                      "model",
                      "gpt-5.3-codex",
                      "modelProvider",
                      "rei_plan",
                      "permissions",
                      "rei-read",
                      "approvalPolicy",
                      "untrusted",
                      "approvalsReviewer",
                      "user"))));
      var thread = response(session, 2, json);
      assertEquals(started.cwd(), thread.path("cwd").asText());
      assertEquals("rei-read", thread.path("activePermissionProfile").path("id").asText());
      assertEquals("readOnly", thread.path("sandbox").path("type").asText());
      assertFalse(thread.path("sandbox").path("networkAccess").asBoolean());
      assertEquals(0, thread.path("instructionSources").size());
    }
    try (var homes = Files.list(root)) {
      assertEquals(0, homes.count());
    }
    assertFalse(ProcessHandle.of(ownedPid).map(ProcessHandle::isAlive).orElse(false));
  }

  private JsonNode response(PlanAppServerRpc.Session session, int id, ObjectMapper json)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      String line = session.poll(Duration.ofMillis(100));
      if (line == null) fail("Native process ended");
      if (line.isEmpty()) continue;
      var value = json.readTree(line);
      if (value.path("id").asInt() == id) {
        assertFalse(value.has("error"), value.toString());
        return value.get("result");
      }
    }
    throw new AssertionError("Native response deadline");
  }
}
