package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Optional no-credential native compatibility check; ordinary CI uses the mocked sessions. */
class WslPlanAppServerProcessTest {
  @TempDir Path root;

  @Test
  @EnabledIfEnvironmentVariable(named = "REI_TEST_WSL_CODEX", matches = "/.+")
  void startsRestrictedThreadWithoutTurnThenClosesNativeProcessAndDeletesOwnHome()
      throws Exception {
    var settings =
        new PlanAppServerProperties(
            true, root, "Ubuntu-24.04", System.getenv("REI_TEST_WSL_CODEX"));
    var launcher = new WslPlanAppServerLauncher(settings);
    var token =
        new TokenSet(
            "fixture",
            "fixture",
            "fixture",
            null,
            "dummy-local-no-inference",
            null,
            Set.of(),
            Instant.now().plusSeconds(60).getEpochSecond());
    var started = launcher.start(token, Duration.ofSeconds(20), () -> false);
    var session = started.session();
    var json = new ObjectMapper();
    String pid;
    try (session) {
      session.send(
          "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"rei\",\"version\":\"1.0\"},\"capabilities\":{\"experimentalApi\":true}}}");
      var initialized = response(session, 1, json);
      assertTrue(initialized.path("userAgent").asText().startsWith("rei/0.154.0 "));
      assertEquals("linux", initialized.path("platformOs").asText());
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
      assertEquals("rei-read", thread.path("activePermissionProfile").path("id").asText());
      assertEquals("readOnly", thread.path("sandbox").path("type").asText());
      assertFalse(thread.path("sandbox").path("networkAccess").asBoolean());
      assertEquals(0, thread.path("instructionSources").size());
      try (var homes = Files.list(root)) {
        pid = Files.readString(homes.findFirst().orElseThrow().resolve("process.pid")).strip();
      }
    }
    try (var homes = Files.list(root)) {
      assertEquals(0, homes.count());
    }
    var command =
        new ProcessBuilder(
            Path.of(System.getenv("SystemRoot"), "System32", "wsl.exe").toString(),
            "--distribution",
            "Ubuntu-24.04",
            "--exec",
            "/bin/kill",
            "-0",
            pid);
    command.environment().clear();
    command.environment().put("SystemRoot", System.getenv("SystemRoot"));
    command.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    command.redirectError(ProcessBuilder.Redirect.DISCARD);
    var check = command.start();
    assertTrue(check.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    assertNotEquals(0, check.exitValue(), "Dedicated Linux process must have exited");
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
        assertFalse(value.has("error"));
        return value.get("result");
      }
    }
    throw new AssertionError("Native response deadline");
  }
}
