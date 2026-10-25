package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretaryRemoteCommandsTest {
  @Test void globalBriefingsIgnoreTheSelectedProjectAndUseExplicitProjectOnlyWhenRequested() {
    assertEquals("/api/v1/secretary/briefing", RemoteCommands.route(List.of("/secretary", "briefing"), "selected", null).path());
    assertEquals("/api/v1/secretary/briefing", RemoteCommands.route(List.of("/secretary", "briefing"), null, null).path());
    assertEquals("/api/v1/secretary/briefing?projectId=explicit", RemoteCommands.route(List.of("/secretary", "briefing", "--project", "explicit"), "selected", null).path());
    var generated = RemoteCommands.route(List.of("/secretary", "review-generate", "--key", "global-one"), "selected", null);
    assertEquals(Map.of("key", "global-one", "review", true), generated.body());
    assertEquals("/api/v1/secretary/briefings/id", RemoteCommands.route(List.of("/secretary", "briefing-show", "id"), "selected", null).path());
    assertEquals("/api/v1/secretary/briefings/settings", RemoteCommands.route(List.of("/secretary", "briefing-settings"), null, null).path());
    assertEquals("/api/v1/secretary/briefings/check", RemoteCommands.route(List.of("/secretary", "briefing-check"), null, null).path());
    assertThrows(IllegalArgumentException.class, () -> RemoteCommands.route(List.of("/secretary", "briefing-settings", "--project", "p"), null, null));
  }
  @Test void preparationAndExecutionModeAreReadOnlyScopedViews() {
    assertEquals("/api/v1/secretary/preparation?projectId=p", RemoteCommands.route(List.of("/secretary", "preparation"), "p", null).path());
    var mode = RemoteCommands.route(List.of("/secretary", "work-mode", "job"), "p", null);
    assertEquals("GET", mode.method());
    assertEquals("/api/v1/secretary/executions/job/mode?projectId=p", mode.path());
  }
  @Test void generatedBriefingsHaveExplicitKeysAndScopedHistory() {
    var generated = RemoteCommands.route(List.of("/secretary", "review-generate", "--key", "evening-one", "--project", "p"), "p", null);
    assertEquals("POST", generated.method());
    assertEquals("/api/v1/secretary/briefings/generate", generated.path());
    assertEquals(Map.of("projectId", "p", "review", true, "key", "evening-one"), generated.body());
    assertThrows(IllegalArgumentException.class, () -> RemoteCommands.route(List.of("/secretary", "briefing-generate"), "p", null));
    assertEquals("/api/v1/secretary/briefings/id?projectId=p", RemoteCommands.route(List.of("/secretary", "briefing-show", "id", "--project", "p"), "p", null).path());
    assertEquals("/api/v1/secretary/briefings/delete", RemoteCommands.route(List.of("/secretary", "briefing-clear"), "p", null).path());
  }
  @Test void connectionsUseFixedPathsAndRequireWriteRevisions() {
    var config=RemoteCommands.route(List.of("/secretary","connection-configure","--name","mail","--service","GMAIL","--resource","me","--connection-enabled"),"p","s");
    assertEquals("PUT",config.method());assertEquals("/api/v1/secretary/connections",config.path());
    assertEquals(-1L,((Map<?,?>)config.body()).get("version"));
    var prepare=RemoteCommands.route(List.of("/secretary","external-prepare","--connection","mail","--key","send:one","--operation","MAIL_SEND","--arguments","{\"to\":\"a@example.invalid\",\"body\":\"review\"}"),"p","s");
    assertEquals("/api/v1/secretary/external-actions",prepare.path());
    assertEquals("MAIL_SEND",((Map<?,?>)prepare.body()).get("operation"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/secretary","external-execute","id"),"p","s"));
    var execute=RemoteCommands.route(List.of("/secretary","external-execute","id","--revision","3"),"p","s");
    assertEquals("/api/v1/secretary/external-actions/id/execute",execute.path());
    assertEquals(3L,((Map<?,?>)execute.body()).get("revision"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/secretary","connection-read","id","--url","https://evil.invalid"),"p","s"));
  }

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
  @org.junit.jupiter.api.Test void boundedExecutionsHaveFixedRoutesAndExplicitRevision() {
    var add=RemoteCommands.route(List.of("/secretary","work-add","task","--revision","2","--key","one","--kind","UPDATE_TASK","--status","WAITING"),"p","s");
    org.junit.jupiter.api.Assertions.assertEquals("/api/v1/secretary/executions",add.path());
    org.junit.jupiter.api.Assertions.assertEquals(2L,((Map<?,?>)add.body()).get("taskRevision"));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/secretary","work-start","work"),"p","s"));
    var control=RemoteCommands.route(List.of("/secretary","work-cancel","work","--revision","3"),"p","s");
    org.junit.jupiter.api.Assertions.assertEquals("/api/v1/secretary/executions/work/cancel",control.path());
  }

}
