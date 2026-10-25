package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** 外部コマンド一覧の結果と状態の契約を検証するテスト。 */
class RemoteCommandsTest {
  @Test void lifeSettingsUseFixedGlobalEndpointsAndStructuredReplacement() {
    assertEquals("/api/v1/life/settings",RemoteCommands.route(List.of("/secretary","life-settings"),null,null).path());
    assertEquals("/api/v1/life/status",RemoteCommands.route(List.of("/secretary","life-status"),null,null).path());
    var request=RemoteCommands.route(List.of("/secretary","life-configure","--settings-json","{\"revision\":1,\"zone\":\"Asia/Tokyo\"}"),null,null);
    assertEquals("PUT",request.method());assertTrue(((Map<?,?>)request.body()).containsKey("settings"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/secretary","life-configure","--settings-json","[]"),null,null));
  }

  /** 再開用の記録履歴保守全体共通およびについて、明示的な反映を必要とすることを確認する。 */
  @Test
  void checkpointHistoryMaintenanceIsGlobalAndRequiresExplicitApply() {
    var status = RemoteCommands.route(List.of("/checkpoint", "status"), null, null);
    assertEquals("GET", status.method());
    assertEquals("/api/v1/checkpoints/history/status", status.path());
    var preview = RemoteCommands.route(List.of("/checkpoint", "cleanup"), null, null);
    assertEquals("GET", preview.method());
    assertEquals("/api/v1/checkpoints/history/cleanup", preview.path());
    var apply = RemoteCommands.route(List.of("/checkpoint", "cleanup", "--apply"), null, null);
    assertEquals("POST", apply.method());
    assertEquals("/api/v1/checkpoints/history/cleanup", apply.path());
    assertEquals(Map.of("apply", true), apply.body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/checkpoint", "status", "--apply"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/checkpoint", "cleanup", "--all"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/checkpoint", "cleanup", "--apply", "extra"), null, null));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void explicitFilesAreResolvedByTheClientSelectedProject() {
    var base = java.nio.file.Path.of("selected-project").toAbsolutePath().normalize();
    var image =
        (Map<?, ?>)
            RemoteCommands.route(
                    List.of("/image", "generate", "scene", "--output", "picture.png"),
                    "project",
                    null,
                    base)
                .body();
    assertEquals(base.resolve("picture.png").toString(), image.get("output"));
    var embed =
        (Map<?, ?>)
            RemoteCommands.route(List.of("/embed", "paper.txt"), "project", null, base).body();
    assertEquals(List.of(base.resolve("paper.txt").toString()), embed.get("documents"));
    var opml =
        (Map<?, ?>)
            RemoteCommands.route(
                    List.of("/feed", "import-opml", "feeds.opml"), "project", null, base)
                .body();
    assertEquals(base.resolve("feeds.opml").toString(), opml.get("path"));
  }

  /** 検索および画像一覧について、シェル選択可能な設定を維持することを確認する。 */
  @Test
  void searchAndImagesKeepShellOptionsAndSummaryCanShowItsLatestResult() {
    var search =
        (Map<?, ?>)
            RemoteCommands.route(
                    List.of(
                        "/search",
                        "query",
                        "--vector-top-k",
                        "8",
                        "--web-top-k",
                        "2",
                        "--threshold",
                        "0.4",
                        "--source",
                        "docs"),
                    "project",
                    "session")
                .body();
    assertEquals(8, search.get("vectorTopK"));
    assertEquals(2, search.get("webTopK"));
    var image =
        (Map<?, ?>)
            RemoteCommands.route(
                    List.of(
                        "/image",
                        "generate",
                        "A",
                        "scene",
                        "--model",
                        "image-test",
                        "--raw",
                        "--size",
                        "512x768",
                        "--output",
                        "picture.png"),
                    "project",
                    null)
                .body();
    assertEquals("A scene", image.get("prompt"));
    assertEquals(true, image.get("raw"));
    assertEquals("image-test", image.get("model"));
    assertTrue(java.nio.file.Path.of((String) image.get("output")).isAbsolute());
    assertEquals(
        "/api/v1/projects/project/summaries/latest",
        RemoteCommands.route(List.of("/summarize"), "project", null).path());
  }

  /** フィード操作一覧について、項目対象範囲を保持することを確認する。 */
  @Test
  void feedOperationsPreserveItemRangeAndOptionalUpdateId() {
    assertEquals(
        "/api/v1/feed/commands/items?limit=10",
        RemoteCommands.route(List.of("/feed", "item", "list"), "project", "session").path());
    assertEquals(
        42L,
        ((Map<?, ?>)
                RemoteCommands.route(
                        List.of("/feed", "item", "summarize", "42"), "project", "session")
                    .body())
            .get("itemId"));
    assertNull(
        ((Map<?, ?>) RemoteCommands.route(List.of("/feed", "update"), "project", null).body())
            .get("feedId"));
    assertEquals(
        7L,
        ((Map<?, ?>)
                RemoteCommands.route(List.of("/feed", "update", "7"), "project", "session").body())
            .get("feedId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/feed", "item", "execute"), "project", "session"));
  }

  /** 取得記録について、固定した受付経路一覧を利用することを確認する。 */
  @Test
  void captureUsesFixedRoutesAndDoesNotApproveRawOutputImplicitly() {
    assertEquals(
        Map.of("projectId", "project", "sessionId", "session"),
        RemoteCommands.route(List.of("/llm", "capture", "next"), "project", "session").body());
    assertEquals(
        "/api/v1/llm/capture/attempts/attempt/show",
        RemoteCommands.route(List.of("/llm", "capture", "show", "attempt"), "project", "session")
            .path());
    assertEquals(
        Map.of("approved", false),
        RemoteCommands.route(List.of("/llm", "capture", "raw", "attempt"), "project", "session")
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/llm", "capture", "execute"), "project", "session"));
  }

  /** 任意返信テキストを維持することを確認する。 */
  @Test
  void socialCommandsHaveNamedRoutesAndKeepOptionalReplyText() {
    assertEquals(
        "/api/v1/bluesky/history-users",
        RemoteCommands.route(List.of("/bsky", "history-users"), "project", "session").path());
    assertEquals(
        "/api/v1/bluesky/history/alice?limit=8",
        RemoteCommands.route(
                List.of("/bsky", "history", "alice", "--limit", "8"), "project", "session")
            .path());
    assertEquals(
        "hello world",
        ((Map<?, ?>)
                RemoteCommands.route(
                        List.of("/bsky", "reply", "at://target", "hello", "world"),
                        "project",
                        "session")
                    .body())
            .get("text"));
    assertNull(
        ((Map<?, ?>)
                RemoteCommands.route(List.of("/bsky", "reply", "at://target"), "project", null)
                    .body())
            .get("text"));
    assertEquals(
        "/api/v1/interest/commands/discover",
        RemoteCommands.route(List.of("/interest", "discover"), "project", "session").path());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/bsky", "execute"), "project", "session"));
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void activityRoutesItsFullDeclaredOperationsWithoutGenericExecution() {
    assertEquals(
        "/api/v1/activity/commands/summary?date=yesterday",
        RemoteCommands.route(
                List.of("/activity", "--verbose", "summary", "yesterday"), "project", "session")
            .path());
    assertEquals(
        "/api/v1/activity/commands/pause",
        RemoteCommands.route(List.of("/activity", "pause"), "project", "session").path());
    var enable = RemoteCommands.route(List.of("/activity", "coaching", "on"), "project", "session");
    assertEquals("/api/v1/activity/commands/coaching/enabled", enable.path());
    assertEquals(Map.of("enabled", true), enable.body());
    assertEquals(
        "/api/v1/activity/commands/classification/unknowns?top=8",
        RemoteCommands.route(
                List.of("/activity", "classification", "unknowns", "--top", "8"),
                "project",
                "session")
            .path());
    assertEquals(
        Map.of("projectId", "project", "sessionId", "session"),
        RemoteCommands.route(
                List.of("/activity", "behavior", "suggest-rules"), "project", "session")
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/activity", "classification", "execute"), "project", "session"));
  }

  /** 明示的な対象範囲を保持することを確認する。 */
  @Test
  void storageHasOnlyNamedOperationsAndPreservesExplicitScopeAndBudgets() {
    assertEquals(
        "/api/v1/storage/status?projectId=project&current=true",
        RemoteCommands.route(
                List.of("/storage", "status", "--project", "current"), "project", "session")
            .path());
    var plan =
        RemoteCommands.route(
            List.of("/storage", "retention", "plan", "--project", "current"), "project", "session");
    assertEquals("/api/v1/storage/retention/plans", plan.path());
    assertEquals(
        Map.of(
            "projectId",
            "project",
            "current",
            true,
            "category",
            "raw-results",
            "maxObjects",
            100,
            "maxBytes",
            16777216L),
        plan.body());
    assertEquals(
        "/api/v1/storage/retention/executions/execution/approve-purge",
        RemoteCommands.route(
                List.of("/storage", "retention", "approve-purge", "execution"),
                "project",
                "session")
            .path());
    assertEquals(
        Map.of("pages", 8),
        RemoteCommands.route(
                List.of("/storage", "maintenance", "incremental-vacuum", "--pages", "8"),
                "project",
                "session")
            .body());
    assertEquals(
        Map.of("windowSeconds", 30L),
        RemoteCommands.route(
                List.of("/storage", "maintenance", "vacuum", "--window-seconds", "30"),
                "project",
                "session")
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/storage", "status", "--project", "other"), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/storage", "maintenance", "vacuum"), "project", "session"));
  }

  /** 埋め込み表現の生成について、検索削除を保持することを確認する。 */
  @Test
  void embedPreservesTheSearchDeleteAndPositionalFileGrammar() {
    var search =
        RemoteCommands.route(
            List.of(
                "/embed",
                "search",
                "A",
                "query",
                "--top-k",
                "3",
                "--threshold",
                "0.6",
                "--source",
                "paper"),
            "project",
            "session");
    assertEquals(
        "/api/v1/vector-documents/search?query=A%20query&topK=3&threshold=0.6&source=paper",
        search.path());
    assertEquals(
        Map.of("docId", "doc", "source", ""),
        RemoteCommands.route(List.of("/embed", "delete", "--doc-id", "doc"), "project", "session")
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/embed", "delete", "--doc-id", "doc", "--source", "paper"),
                "project",
                "session"));
    assertEquals(
        Map.of(
            "projectId",
            "project",
            "sessionId",
            "session",
            "documents",
            List.of(java.nio.file.Path.of("paper.txt").toAbsolutePath().normalize().toString())),
        RemoteCommands.route(List.of("/embed", "paper.txt"), "project", "session").body());
  }

  /** 軽量CLIでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void googleCalendarAndGoogleTasksKeepTheirDistinctShellGrammar() {
    var event =
        RemoteCommands.route(
            List.of(
                "/schedule",
                "add",
                "A",
                "meeting",
                "--start",
                "2026-10-11T09:00:00",
                "--end",
                "2026-10-11T10:00:00"),
            "project",
            "session");
    assertEquals("/api/v1/google-operations/calendar/add", event.path());
    assertEquals("A meeting", ((Map<?, ?>) event.body()).get("title"));
    assertEquals(
        "2026-10-11",
        ((Map<?, ?>)
                RemoteCommands.route(
                        List.of("/schedule", "list", "--date", "2026-10-11"), "project", "session")
                    .body())
            .get("date"));
    assertEquals(
        Map.of("projectId", "project", "sessionId", "session"),
        RemoteCommands.route(List.of("/task", "auth"), "project", "session").body());
    var task =
        RemoteCommands.route(List.of("/task", "add", "Review", "paper"), "project", "session");
    var body = (Map<?, ?>) task.body();
    assertEquals("Review paper", body.get("title"));
    assertEquals(3, body.get("priority"));
    assertNull(body.get("due"));
    assertEquals(Set.of("projectId", "sessionId", "title", "priority", "due"), body.keySet());
    assertEquals(
        "/api/v1/google-operations/tasks/12/complete",
        RemoteCommands.route(List.of("/task", "done", "12"), "project", "session").path());
    assertEquals(
        "POST",
        RemoteCommands.route(List.of("/task", "delete", "12"), "project", "session").method());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/schedule", "add", "Meeting", "--start", "2026-10-11T09:00:00"),
                "project",
                "session"));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void googleTaskAddAndListSendOnlyTheirSupportedFields() {
    var added =
        RemoteCommands.route(
            List.of("/task", "add", "Review", "paper", "--due", "2026-10-13", "--priority", "2"),
            "project",
            "session");
    assertEquals("POST", added.method());
    assertEquals("/api/v1/google-operations/tasks/add", added.path());
    assertEquals(
        Map.of(
            "projectId",
            "project",
            "sessionId",
            "session",
            "title",
            "Review paper",
            "due",
            "2026-10-13",
            "priority",
            2),
        added.body());
    assertEquals(
        Map.of("projectId", "project", "sessionId", "session"),
        RemoteCommands.route(List.of("/task"), "project", "session").body());
    var listed =
        RemoteCommands.route(
            List.of("/task", "list", "--priority", "2", "--due-before", "2026-10-14"),
            "project",
            "session");
    assertEquals("POST", listed.method());
    assertEquals("/api/v1/google-operations/tasks/list", listed.path());
    assertEquals(
        Map.of(
            "projectId",
            "project",
            "sessionId",
            "session",
            "priority",
            2,
            "dueBefore",
            "2026-10-14"),
        listed.body());
  }

  /** Googleタスクコマンド一覧について、削除済みタグを受け付けないことを確認する。 */
  @Test
  void googleTaskCommandsRejectRemovedTagAndOtherUnknownOptions() {
    for (String action : List.of("add", "list"))
      for (String option : List.of("--tag", "--unknown")) {
        var words = new ArrayList<>(List.of("/task", action));
        if (action.equals("add")) words.add("Review paper");
        words.add(option);
        words.add("science");
        assertThrows(
            IllegalArgumentException.class,
            () -> RemoteCommands.route(words, "project", "session"));
        words.removeLast();
        words.removeLast();
        words.add(option + "=science");
        assertThrows(
            IllegalArgumentException.class,
            () -> RemoteCommands.route(words, "project", "session"));
      }
  }

  /** 応答動作モードについて、シェル任意真偽値入力規則を受け付けられることを確認する。 */
  @Test
  void responseModeAcceptsShellOptionalBooleanSyntax() {
    assertEquals(
        Map.of("projectId", "project", "style", "CONVERSATION", "voiceOnly", false),
        RemoteCommands.route(
                List.of("/mode", "conversation", "--voice-only=false"), "project", "session")
            .body());
    assertEquals(
        Map.of("projectId", "project", "style", "CONVERSATION", "voiceOnly", false),
        RemoteCommands.route(
                List.of("/mode", "conversation", "--voice-only", "false"), "project", "session")
            .body());
    assertEquals(
        Map.of("projectId", "project", "style", "CONVERSATION", "voiceOnly", true),
        RemoteCommands.route(List.of("/mode", "conversation", "--voice-only"), "project", "session")
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(List.of("/mode", "normal", "--voice-only"), "project", "session"));
  }

  /** 軽量CLIでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void memoryMaintenancePreservesApprovalAliasesAndRequiresSelectedSession() {
    var summary =
        RemoteCommands.route(List.of("/memory", "summarize", "--save=false"), "project", "session");
    assertEquals("/api/v1/projects/project/memory/summarize", summary.path());
    assertEquals(Map.of("sessionId", "session", "approve", false), summary.body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/memory", "summarize", "--approve=true", "--save=false"),
                "project",
                "session"));
    assertEquals(
        Map.of("sessionId", "session", "approve", true),
        RemoteCommands.route(List.of("/memory", "consolidate", "--save"), "project", "session")
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/memory", "summarize"), "project", null));
    assertEquals(
        Map.of(
            "directory", java.nio.file.Path.of("export").toAbsolutePath().normalize().toString()),
        RemoteCommands.route(List.of("/memory", "export", "--dir", "export"), "project", "session")
            .body());
  }

  /** 軽量CLIでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void sleepUsesTheSelectedSessionAndObservedCancellationRevision() {
    var preview = RemoteCommands.route(List.of("/sleep", "preview"), "project", "session");
    assertEquals("/api/v1/projects/project/memory/sleep", preview.path());
    assertEquals(Map.of("sessionId", "session", "preview", true), preview.body());
    assertEquals(
        Map.of("sessionId", "session", "preview", false),
        RemoteCommands.route(List.of("/sleep"), "project", "session").body());
    assertEquals(
        "/api/v1/projects/project/memory/sleep/status?sessionId=session",
        RemoteCommands.route(List.of("/sleep", "status"), "project", "session").path());
    assertEquals(
        Map.of("revision", 2L),
        RemoteCommands.route(
                List.of("/sleep", "cancel-request", "session", "--revision", "2"),
                "project",
                "session")
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/sleep"), "project", null));
  }

  /** 軽量CLIでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void reflectionReviewPreservesTheOwningSessionAndRevisionEvidenceOptions() {
    assertEquals(
        "/api/v1/projects/project/reflections",
        RemoteCommands.route(List.of("/reflection", "list"), "project", "session").path());
    assertEquals(
        "/api/v1/projects/project/reflections/runs",
        RemoteCommands.route(List.of("/reflection", "runs"), "project", "session").path());
    assertEquals(
        Map.of("sessionId", "session"),
        RemoteCommands.route(List.of("/reflection", "collect-run", "run"), "project", "session")
            .body());
    var review =
        RemoteCommands.route(
            List.of(
                "/reflection",
                "lesson-validate",
                "lesson",
                "--revision",
                "2",
                "--evidence-hash",
                "a".repeat(64),
                "--note",
                "reviewed"),
            "project",
            "session");
    assertEquals("/api/v1/projects/project/reflections/lessons/lesson/validate", review.path());
    var body = (Map<?, ?>) review.body();
    assertEquals("session", body.get("sessionId"));
    assertEquals(2L, body.get("revision"));
    assertEquals("reviewed", body.get("note"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/reflection", "lesson-validate", "lesson", "--revision", "2"),
                "project",
                null));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void subagentDefinitionManagementHasOnlyFixedRoutesAndLocalValidationPaths() {
    assertEquals(
        "/api/v1/subagents", RemoteCommands.route(List.of("/subagent", "list"), null, null).path());
    assertEquals(
        "/api/v1/subagents/reviewer",
        RemoteCommands.route(List.of("/subagent", "show", "reviewer"), null, null).path());
    assertEquals(
        "/api/v1/subagents/reload",
        RemoteCommands.route(List.of("/subagent", "reload"), null, null).path());
    assertEquals(
        Map.of("id", "reviewer"),
        RemoteCommands.route(List.of("/subagent", "init", "reviewer"), null, null).body());
    assertEquals(
        Map.of(
            "file", java.nio.file.Path.of("reviewer.yaml").toAbsolutePath().normalize().toString()),
        RemoteCommands.route(List.of("/subagent", "validate", "reviewer.yaml"), null, null).body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/subagent", "execute", "arbitrary"), null, null));
  }

  /** 軽量CLIでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void dependencyShellAnswerUsesValueOptionAndNamedCheckCancelOperations() {
    var answer =
        RemoteCommands.route(
            List.of("/dependency", "answer", "dep", "--value", "Choice A"), "project", "session");
    assertEquals("/api/v1/projects/project/dependencies/dep/human-answer", answer.path());
    assertEquals(Map.of("value", "Choice A"), answer.body());
    assertEquals(
        "/api/v1/projects/project/dependencies/dep/check",
        RemoteCommands.route(List.of("/dependency", "check", "dep"), "project", "session").path());
    assertEquals(
        "/api/v1/projects/project/dependencies/dep/cancel",
        RemoteCommands.route(List.of("/dependency", "cancel", "dep"), "project", "session").path());
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void configAndSkillUseNamedOperationsAndShellOptions() {
    assertEquals(
        "/api/v1/config/path", RemoteCommands.route(List.of("/config", "path"), null, null).path());
    assertEquals(
        Map.of("force", true),
        RemoteCommands.route(List.of("/config", "init", "--force"), null, null).body());
    assertEquals(
        Map.of("force", false),
        RemoteCommands.route(List.of("/config", "init"), null, null).body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/config", "init", "arbitrary-path"), null, null));
    assertEquals(
        "/api/v1/skills/report", RemoteCommands.route(List.of("/skill"), null, null).path());
    assertEquals(
        "/api/v1/skills/sample/report",
        RemoteCommands.route(List.of("/skill", "show", "sample"), null, null).path());
    assertEquals(
        "/api/v1/skills/reload-report",
        RemoteCommands.route(List.of("/skill", "reload"), null, null).path());
  }

  /** 軽量CLIでの対話で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void researchRequestsUseOnlyTheirNamedDomainRoutes() {
    assertEquals(
        "/api/v1/paper/operations",
        RemoteCommands.route(
                List.of("/paper", "search", "graph theory", "--limit", "2"), "project", "session")
            .path());
    assertEquals(
        "/api/v1/material-reviews",
        RemoteCommands.route(
                List.of("/material-review", "beginner", "--entry", "index.html"),
                "project",
                "session")
            .path());
    var agent =
        RemoteCommands.route(
            List.of("/agent", "codex", "review", "src/main"), "project", "session");
    assertEquals("/api/v1/external-agent/operations", agent.path());
    assertEquals(
        Map.of(
            "projectId",
            "project",
            "sessionId",
            "session",
            "agent",
            "codex",
            "action",
            "review",
            "target",
            "src/main"),
        agent.body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/paper", "execute", "anything"), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/agent", "arbitrary", "execute", "anything"), "project", "session"));
  }

  /** 承認済みハッシュ値を必要とすることを確認する。 */
  @Test
  void recoveryHasSeparateNamedRoutesAndRequiresTheApprovedHash() {
    String id = "12345678-1234-1234-1234-123456789abc", hash = "a".repeat(64);
    var document =
        RemoteCommands.route(List.of("/document", "rollback", id, hash), "project", "session");
    assertEquals("/api/v1/document/recovery", document.path());
    assertEquals(
        Map.of(
            "projectId",
            "project",
            "sessionId",
            "session",
            "action",
            "rollback",
            "id",
            id,
            "hash",
            hash),
        document.body());
    assertEquals(
        "/api/v1/repair/recovery",
        RemoteCommands.route(List.of("/repair", "show", id), "project", "session").path());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(List.of("/document", "execute", id, hash), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/repair", "apply", id), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/document", "show", id, hash), "project", "session"));
  }

  /** 軽量CLIでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void historyPreservesSessionPaginationLegacyRangesAndSearchScopes() {
    assertEquals(
        "/api/v1/history/report",
        RemoteCommands.route(List.of("/history"), "project", "session").path());
    assertEquals(
        "/api/v1/history/report?limit=5&projectId=other&cursor=next",
        RemoteCommands.route(
                List.of("/history", "--limit", "5", "--project-id", "other", "--cursor", "next"),
                "project",
                "session")
            .path());
    assertEquals(
        "/api/v1/history/conversations?selectedProjectId=project&limit=50&offset=0",
        RemoteCommands.route(List.of("/history", "list"), "project", "session").path());
    assertEquals(
        "/api/v1/history/show?selectedProjectId=project",
        RemoteCommands.route(List.of("/history", "show"), "project", "session").path());
    assertEquals(
        "/api/v1/history/show?selectedProjectId=project&conversation=chosen&limit=10&cursor=next",
        RemoteCommands.route(
                List.of("/history", "show", "chosen", "--limit", "10", "--cursor", "next"),
                "project",
                "session")
            .path());
    assertEquals(
        "/api/v1/history/conversation?selectedProjectId=project&sessionId=session&last=20&all=false",
        RemoteCommands.route(List.of("/history", "show", "--last", "20"), "project", "session")
            .path());
    assertEquals(
        "/api/v1/history/search?selectedProjectId=project&query=two%20words&scope=ALL_PROJECTS&limit=10",
        RemoteCommands.route(
                List.of("/history", "search", "two", "words", "--all"), "project", "session")
            .path());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/history", "show", "--last", "10", "--all"), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/history", "show", "--cursor", "next"), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/history", "show", "id", "--limit", "5", "--all"), "project", "session"));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void timingDefaultsAndArgumentOrderMatchShell() {
    assertEquals(
        "/api/v1/timing?runId=last&details=false&projectId=project&sessionId=session",
        RemoteCommands.route(List.of("/timing"), "project", "session").path());
    assertEquals(
        "/api/v1/timing?runId=run&details=true&projectId=project&sessionId=session",
        RemoteCommands.route(List.of("/timing", "--details", "run"), "project", "session").path());
    assertEquals(
        "/api/v1/timing?runId=last&details=false",
        RemoteCommands.route(List.of("/timing"), null, null).path());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/timing", "--details", "--details"), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/timing", "one", "two"), "project", "session"));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void profileAndBriefingHaveAllLegacyReadForms() {
    assertEquals(
        "/api/v1/profile/report", RemoteCommands.route(List.of("/profile"), null, null).path());
    assertEquals(
        "/api/v1/profile/report",
        RemoteCommands.route(List.of("/profile", "summary"), null, null).path());
    assertEquals(
        "/api/v1/profile/path",
        RemoteCommands.route(List.of("/profile", "path"), null, null).path());
    assertEquals(
        "/api/v1/profile/chart?bucketSeconds=60&width=40",
        RemoteCommands.route(List.of("/profile", "chart"), null, null).path());
    assertEquals(
        "/api/v1/profile/chart?bucketSeconds=5&width=10",
        RemoteCommands.route(
                List.of("/profile", "chart", "--width", "10", "--bucket-seconds", "5"), null, null)
            .path());
    assertEquals(
        "/api/v1/profile/mermaid?limit=12",
        RemoteCommands.route(List.of("/profile", "mermaid"), null, null).path());
    assertEquals(
        "/api/v1/briefing/commands/today",
        RemoteCommands.route(List.of("/briefing", "today"), "project", null).path());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/profile", "path", "unexpected"), null, null));
  }

  /** 軽量CLIでの対話で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void doctorChecksRemainExplicitBoundedAndDeduplicated() {
    assertEquals(
        "/api/v1/doctor?details=false",
        RemoteCommands.route(List.of("/doctor"), null, null).path());
    assertEquals(
        "/api/v1/doctor?details=true",
        RemoteCommands.route(List.of("/doctor", "--details"), null, null).path());
    var active =
        RemoteCommands.route(
            List.of(
                "/doctor",
                "--check",
                "connectivity",
                "--details",
                "--check=CODEX",
                "--check",
                "connectivity"),
            "project",
            "session");
    assertEquals("/api/v1/diagnostics", active.path());
    assertEquals(List.of("connectivity", "codex"), ((Map<?, ?>) active.body()).get("checks"));
    assertEquals("session", ((Map<?, ?>) active.body()).get("sessionId"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(List.of("/doctor", "--check", "arbitrary"), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/doctor", "--check", "inference"), null, null));
  }

  /** 再開用の記録および再開について、旧形式シェル入力文法を受け付けられることを確認する。 */
  @Test
  void checkpointAndResumeAcceptTheLegacyShellGrammar() {
    var saved = RemoteCommands.route(List.of("/checkpoint"), "project", "session");
    assertEquals("POST", saved.method());
    assertEquals("/api/v1/projects/project/checkpoints", saved.path());
    assertEquals(Map.of("sessionId", "session"), saved.body());
    assertEquals(
        "/api/v1/projects/project/checkpoints/report",
        RemoteCommands.route(List.of("/resume"), "project", null).path());
    assertEquals(
        "/api/v1/projects/project/checkpoints/report/list",
        RemoteCommands.route(List.of("/resume", "list"), "project", null).path());
    assertEquals(
        "/api/v1/projects/project/checkpoints/task/report",
        RemoteCommands.route(List.of("/resume", "show", "task"), "project", null).path());
    assertEquals(
        "/api/v1/projects/project/checkpoints/task/abandon",
        RemoteCommands.route(List.of("/resume", "abandon", "task"), "project", null).path());
    assertEquals(
        "/api/v1/projects/project/checkpoints/task/resume",
        RemoteCommands.route(List.of("/resume", "task"), "project", null).path());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/checkpoint"), "project", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/resume", "show", "task", "extra"), "project", null));
  }

  /** 記憶読み取るおよび記憶の削除について、選択中プロジェクトを利用することを確認する。 */
  @Test
  void memoryReadsAndForgetUseSelectedProjectAndShellPagination() {
    assertEquals(
        "/api/v1/projects/project/memory?limit=20&offset=0",
        RemoteCommands.route(List.of("/memory", "list"), "project", null).path());
    assertEquals(
        "/api/v1/projects/project/memory?limit=5&offset=10",
        RemoteCommands.route(
                List.of("/memory", "list", "--offset", "10", "--limit", "5"), "project", null)
            .path());
    assertEquals(
        "/api/v1/projects/project/memory/search?query=two%20words&limit=10",
        RemoteCommands.route(List.of("/memory", "search", "two", "words"), "project", null).path());
    assertEquals(
        "/api/v1/projects/project/memory/item",
        RemoteCommands.route(List.of("/memory", "show", "item"), "project", null).path());
    assertEquals(
        "/api/v1/projects/project/memory/item/forget",
        RemoteCommands.route(List.of("/memory", "forget", "item"), "project", null).path());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/memory", "search", "query", "--unknown"), "project", null));
  }

  /** フィードおよびリマインダーについて、シェル選択項目名前一覧を受け付けられることを確認する。 */
  @Test
  void feedAndReminderAcceptShellOptionNamesAndDeletionGrammar() {
    assertEquals(
        Map.of("url", "https://example.test/feed", "displayName", "example"),
        RemoteCommands.route(
                List.of("/feed", "add", "https://example.test/feed", "--name", "example"),
                null,
                null)
            .body());
    assertEquals(
        Map.of("displayName", "renamed", "enabled", false),
        RemoteCommands.route(
                List.of("/feed", "edit", "1", "--name", "renamed", "--disabled"), null, null)
            .body());
    for (String command : List.of("/feed", "/reminder", "/memory"))
      assertEquals(
          "DELETE", RemoteCommands.route(List.of(command, "delete", "1"), null, null).method());
    var reminder =
        (Map<?, ?>)
            RemoteCommands.route(
                    List.of(
                        "/reminder",
                        "add",
                        "remember",
                        "the",
                        "fixture",
                        "--at",
                        "2026-10-11T09:00:00Z"),
                    null,
                    null)
                .body();
    assertEquals("remember the fixture", reminder.get("message"));
    assertEquals("2026-10-11T09:00:00Z", reminder.get("at"));
    var before =
        (Map<?, ?>)
            RemoteCommands.route(
                    List.of(
                        "/reminder",
                        "add",
                        "--target",
                        "2026-10-11T09:00:00Z",
                        "--minutes-before",
                        "30",
                        "fixture"),
                    null,
                    null)
                .body();
    assertEquals("fixture", before.get("message"));
    assertEquals(30L, before.get("minutesBefore"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of(
                    "/reminder",
                    "add",
                    "fixture",
                    "--at",
                    "2026-10-11T09:00:00Z",
                    "--target",
                    "2026-10-11T09:00:00Z",
                    "--minutes-before",
                    "30"),
                null,
                null));
  }

  /** 関心一覧について、シェル時選択項目を利用することを確認する。 */
  @Test
  void interestListUsesShellHourOptionWithoutModelSubmission() {
    assertEquals(
        "/api/v1/interest/commands/list?hours=48",
        RemoteCommands.route(List.of("/interest", "list", "--hours", "48"), null, null).path());
  }

  /** 軽量CLIでの対話で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void attentionDeliveryRetainsExplicitDuplicateRiskAcknowledgment() {
    assertEquals(
        "GET",
        RemoteCommands.route(List.of("/attention", "delivery", "item"), "project", null).method());
    assertEquals(
        Map.of("retry", false, "acknowledgeDuplicateRisk", false),
        RemoteCommands.route(List.of("/attention", "deliver", "item"), "project", null).body());
    assertEquals(
        Map.of("retry", true, "acknowledgeDuplicateRisk", true),
        RemoteCommands.route(
                List.of("/attention", "retry-delivery", "item", "--acknowledge-duplicate-risk"),
                "project",
                null)
            .body());
    assertEquals(
        Map.of("retry", true, "acknowledgeDuplicateRisk", false),
        RemoteCommands.route(List.of("/attention", "retry-delivery", "item"), "project", null)
            .body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/attention", "deliver", "item", "--acknowledge-duplicate-risk"),
                "project",
                null));
  }

  /** シェル予算一覧を保持することを確認する。 */
  @Test
  void goalCreationPreservesShellBudgetsAndCriterionOptions() {
    var request =
        RemoteCommands.route(
            List.of(
                "/goal", "create", "objective", "--file", "out.txt", "--sha256", "a".repeat(64)),
            "project",
            "session");
    assertEquals("/api/v1/projects/project/goals", request.path());
    assertEquals("session", ((Map<?, ?>) request.body()).get("sessionId"));
    assertEquals(3L, ((Map<?, ?>) request.body()).get("maxRuns"));
    assertEquals(20L, ((Map<?, ?>) request.body()).get("maxLlmCalls"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of(
                    "/goal", "create", "objective", "--file", "out.txt", "--criteria-json", "[]"),
                "project",
                "session"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of(
                    "/goal",
                    "create",
                    "objective",
                    "--file",
                    "out.txt",
                    "--sha256",
                    "a".repeat(64)),
                "project",
                null));
  }

  /** 目標完了について、JSON変換対象データを送信することを確認する。 */
  @Test
  void goalCompletionSendsJsonObjectAndRequiresExactlyOneDefinitionOrProof() {
    var request =
        RemoteCommands.route(
            List.of("/goal", "completion", "goal", "--completion-json", "{\"criteria\":[]}"),
            "project",
            "session");
    assertEquals("PUT", request.method());
    assertEquals("/api/v1/projects/project/goals/goal/completion", request.path());
    assertTrue(request.body() instanceof com.fasterxml.jackson.databind.JsonNode);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of(
                    "/goal", "completion", "goal", "--completion-json", "{}", "--proof-json", "{}"),
                "project",
                "session"));
    for (String invalid : List.of("{} trailing", "{\"criteria\":[],\"criteria\":[{}]}"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RemoteCommands.route(
                  List.of("/goal", "completion", "goal", "--completion-json", invalid),
                  "project",
                  "session"));
  }

  /** 追加引数を受け付けないことを確認する。 */
  @Test
  void modelCommandsHaveNamedRoutesAndRejectExtraArguments() {
    assertEquals("/api/v1/models", RemoteCommands.route(List.of("/models"), null, null).path());
    assertEquals(
        "/api/v1/models/current", RemoteCommands.route(List.of("/model"), null, null).path());
    var selected = RemoteCommands.route(List.of("/model", "fixture"), null, null);
    assertEquals("PUT", selected.method());
    assertEquals(Map.of("model", "fixture"), selected.body());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/models", "extra"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/model", "fixture", "extra"), null, null));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellDenialAliasAndReconciliationRequireExplicitAcknowledgment() {
    assertEquals(
        Map.of("approved", false),
        RemoteCommands.route(List.of("/approval", "deny", "request"), "project", "session").body());
    for (String command : List.of("/goal", "/timer")) {
      var request =
          RemoteCommands.route(
              List.of(
                  command,
                  "reconcile",
                  "item",
                  "--run-id",
                  "run",
                  "--acknowledge-uncertain-side-effects"),
              "project",
              "session");
      assertEquals(
          Map.of("expectedRunId", "run", "acknowledgeUncertainSideEffects", true), request.body());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RemoteCommands.route(
                  List.of(command, "reconcile", "item", "--run-id", "run"), "project", "session"));
    }
  }

  /** 目標待機中について、観測形式のバージョンを利用することを確認する。 */
  @Test
  void goalWaitingUsesObservedVersionAndShellOptionNames() {
    assertEquals(
        "GET",
        RemoteCommands.route(List.of("/goal", "wait", "goal"), "project", "session").method());
    assertEquals(
        Map.of("dependencyId", "dependency", "reason", "blocked", "sessionId", "session"),
        RemoteCommands.route(
                List.of(
                    "/goal",
                    "wait",
                    "goal",
                    "--dependency-id",
                    "dependency",
                    "--wait-reason",
                    "blocked"),
                "project",
                "session")
            .body());
    for (String action : List.of("activate", "resume", "cancel")) {
      var request =
          RemoteCommands.route(
              List.of("/goal", "wait-" + action, "goal", "--wait-version", "2"),
              "project",
              "session");
      assertEquals("/api/v1/projects/project/goals/goal/wait/" + action, request.path());
      assertEquals(Map.of("expectedVersion", 2L), request.body());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/goal", "wait-resume", "goal", "--wait-version", "0"),
                "project",
                "session"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of(
                    "/goal",
                    "wait",
                    "goal",
                    "--dependency-id",
                    "first",
                    "--dependency-id",
                    "second"),
                "project",
                "session"));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @org.junit.jupiter.api.Test
  void responseStyleIsSeparateFromRunAccess() {
    var request =
        RemoteCommands.route(
            java.util.List.of("/mode", "conversation", "--voice-only"), "project", "session");
    org.junit.jupiter.api.Assertions.assertEquals("PATCH", request.method());
    org.junit.jupiter.api.Assertions.assertEquals(
        "/api/v1/sessions/session/response-style", request.path());
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.Map.of("projectId", "project", "style", "CONVERSATION", "voiceOnly", true),
        request.body());
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void projectControlsCarryExplicitOwnershipAndNamedOperationsOnly() {
    var request =
        RemoteCommands.route(List.of("/approval", "approve", "request"), "project", "session");
    assertEquals("/api/v1/projects/project/approvals/request/decision", request.path());
    assertEquals(Map.of("approved", true), request.body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(List.of("/approval", "execute", "request"), "project", "session"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/sh", "rm", "something"), "project", "session"));
  }

  /** 履歴およびタスク一覧について、実際の公開API受付経路一覧を利用することを確認する。 */
  @Test
  void historyAndTasksUseTheirActualPublicApiRoutes() {
    assertEquals(
        "/api/v1/history/show?selectedProjectId=project",
        RemoteCommands.route(List.of("/history", "show"), "project", "session").path());
    assertEquals(
        "/api/v1/tasks?projectId=project",
        RemoteCommands.route(List.of("/tasks", "list"), "project", "session").path());
    assertEquals(
        "/api/v1/projects/project/attention/item/ack",
        RemoteCommands.route(List.of("/attention", "ack", "item"), "project", "session").path());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/checkpoint", "list"), null, null));
  }

  /** 依存条件回答一覧について、引用済みテキストを保持することを確認する。 */
  @Test
  void dependencyAnswersPreserveQuotedTextAndExpectedRevision() {
    var request =
        RemoteCommands.route(
            List.of("/dependency", "answer", "dependency", "3", "quoted answer"), "project", null);
    assertEquals(Map.of("expectedVersion", 3L, "answer", "quoted answer"), request.body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/dependency", "answer", "dependency", "-1", "answer"), "project", null));
  }
}
