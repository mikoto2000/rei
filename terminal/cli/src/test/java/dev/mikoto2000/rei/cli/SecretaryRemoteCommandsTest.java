package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretaryRemoteCommandsTest {
  @Test
  void proposalsAndFeedbackUseRevisionScopedEndpointsAndExplicitSettings() {
    var configure = RemoteCommands.route(List.of("/secretary", "orchestrator-configure", "--proposals-enabled", "--stopped", "--minimum-seconds", "120"), "p", null);
    assertEquals("/api/v1/secretary/orchestrator/settings", configure.path());
    assertEquals(true, ((Map<?, ?>) configure.body()).get("stopped"));
    var feedback = RemoteCommands.route(List.of("/secretary", "feedback", "id", "--revision", "3", "--reaction", "LATER", "--delay-seconds", "600"), "p", null);
    assertEquals("/api/v1/secretary/suggestions/id/feedback", feedback.path());
    assertEquals(Map.of("projectId", "p", "revision", 3L, "reaction", "LATER", "delaySeconds", 600L), feedback.body());
    assertThrows(IllegalArgumentException.class, () -> RemoteCommands.route(List.of("/secretary", "feedback", "id", "--reaction", "ALLOW_AUTO"), "p", null));
    assertThrows(IllegalArgumentException.class, () -> RemoteCommands.route(List.of("/secretary", "feedback", "id", "--revision", "0", "--reaction", "AUTO_APPROVE"), "p", null));
    assertTrue(RemoteCommands.route(List.of("/secretary", "suggestions"), "p", null).path().contains("/suggestions?"));
  }
  @Test
  void followupConfigurationAndReportsUseFixedAuthenticatedEndpoints() {
    var configure =
        RemoteCommands.route(
            List.of(
                "/secretary",
                "configure",
                "--watch-enabled",
                "--notifications",
                "--zone",
                "Asia/Tokyo",
                "--briefing-time",
                "09:00"),
            "p",
            null);
    assertEquals("PUT", configure.method());
    assertEquals("/api/v1/secretary/settings", configure.path());
    assertEquals(true, ((Map<?, ?>) configure.body()).get("enabled"));
    assertEquals(true, ((Map<?, ?>) configure.body()).get("notifications"));
    var report =
        RemoteCommands.route(List.of("/secretary", "review", "--date", "2026-10-26"), "p", null);
    assertEquals("GET", report.method());
    assertTrue(report.path().contains("/api/v1/secretary/review?"));
    assertTrue(report.path().contains("date=2026-10-26"));
    var check = RemoteCommands.route(List.of("/secretary", "check"), "p", null);
    assertEquals(Map.of("projectId", "p"), check.body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/secretary", "review", "--root", "elsewhere"), "p", null));
  }

  @Test
  void readsUseSelectedProjectAndExplicitOverrides() {
    assertTrue(RemoteCommands.NAMES.contains("/secretary"));
    var list = RemoteCommands.route(List.of("/secretary", "list", "--limit", "20"), "p", "s");
    assertEquals("GET", list.method());
    assertTrue(list.path().contains("projectId=p"));
    assertTrue(list.path().contains("limit=20"));
    var detail =
        RemoteCommands.route(
            List.of("/secretary", "sources", "id", "--project", "other"), "p", null);
    assertEquals("/api/v1/secretary/tasks/id/sources?projectId=other", detail.path());
  }

  @Test
  void explicitRegistrationAndExtractionUseIdempotencyKeys() {
    var add =
        RemoteCommands.route(
            List.of(
                "/secretary",
                "add",
                "--key",
                "manual:1",
                "--title",
                "release",
                "--due",
                "2026-10-30",
                "--tag",
                "a,b",
                "--ref",
                "run:1"),
            "p",
            null);
    assertEquals("POST", add.method());
    assertEquals("/api/v1/secretary/tasks", add.path());
    var body = (Map<?, ?>) add.body();
    assertEquals("manual:1", body.get("key"));
    assertEquals(List.of("a", "b"), body.get("tags"));
    var extract =
        RemoteCommands.route(
            List.of("/secretary", "extract", "--key", "event", "--title", "作成したい"), "p", null);
    assertEquals("/api/v1/secretary/tasks/extract", extract.path());
    assertEquals("event", ((Map<?, ?>) extract.body()).get("eventId"));
  }

  @Test
  void mutationsCarryRevisionAndCannotFallThroughToModelInput() {
    var done =
        RemoteCommands.route(List.of("/secretary", "done", "id", "--revision", "2"), "p", null);
    assertEquals("/api/v1/secretary/tasks/id/transition", done.path());
    assertEquals(Map.of("projectId", "p", "revision", 2L, "status", "DONE"), done.body());
    var update =
        RemoteCommands.route(
            List.of("/secretary", "update", "id", "--title", "release", "--revision", "3"),
            "p",
            null);
    assertEquals("PUT", update.method());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/secretary", "add", "--title", "task"), "p", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/secretary", "list", "--root", "anywhere"), "p", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/secretary", "list"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/secretary", "done", "id", "--revision", "-1"), "p", null));
  }
}
