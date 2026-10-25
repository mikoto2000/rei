package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretaryRemoteCommandsTest {
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
