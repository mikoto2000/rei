package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 再開概要保存と検索の窓口の結果と状態の契約を検証するテスト。 */
class ResumeOverviewRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使うリポジトリ。 */
  PersistentCheckpointRepository repo;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void open() {
    var ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("overview.db"));
    repo = new PersistentCheckpointRepository(ds, new CheckpointProperties());
  }

  @Test
  void structuredTaskStateAndLegacyHeadsRemainOnTheSqlProjectionPath() throws Exception {
    var saved = save("structured", "INTERRUPTED", 0);
    var fields = repo.fields(saved);
    fields.put(
        "taskState",
        new dev.mikoto2000.rei.context.TaskStateSnapshot(
            "review docs",
            "IN_PROGRESS",
            List.of("introduction reviewed"),
            List.of("repair examples")));
    repo.save(repo.fields(fields), saved.revision(), "structured-task-state");
    save("legacy", "INTERRUPTED", 0);
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var s = c.createStatement()) {
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET snapshot=json_remove(snapshot,'$.taskState') WHERE task='legacy'");
    }
    var projected = org.mockito.Mockito.spy(repo);
    var candidates = projected.overviewCandidates("A", Instant.EPOCH);
    assertEquals(
        Set.of("structured", "legacy"),
        candidates.stream()
            .map(ResumeOverview.Candidate::taskId)
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(candidates.stream().noneMatch(ResumeOverview.Candidate::fallback));
    org.mockito.Mockito.verify(projected, org.mockito.Mockito.never())
        .get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  /** 検証用に、後から参照できるよう、指定された情報を保存する。 */
  PersistentCheckpoint save(String task, String status, long revision) {
    var state = PersistentCheckpoint.initial(task, "A", "session", "run", directory, "request");
    var fields = repo.fields(state);
    fields.put("status", status);
    return repo.save(repo.fields(fields), revision, task + revision);
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void aggregatesLatestRevisionBeforeFilteringAndDoesNotMixProjects() {
    save("old", "INTERRUPTED", 0);
    save("done", "INTERRUPTED", 0);
    save("done", "COMPLETED", 1);
    repo.save(
        PersistentCheckpoint.initial("foreign", "B", "s", "r", directory, "foreign"), 0, "start");
    var rows = repo.overviewCandidates("A", Instant.now().minus(Duration.ofDays(30)));
    assertEquals(1, rows.size());
    assertEquals("old", rows.getFirst().taskId());
  }

  /** 作業の中断と再開で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void distinguishesActiveAndLostRunningOwnership() {
    save("active", "RUNNING", 0);
    save("lost", "RUNNING", 0);
    repo.acquire("A", "active", "r");
    var rows = repo.overviewCandidates("A", Instant.EPOCH);
    assertEquals(List.of("lost"), rows.stream().map(ResumeOverview.Candidate::taskId).toList());
    assertEquals("RUNNING", rows.getFirst().savedStatus());
    assertTrue(rows.getFirst().notice().contains("所有権"));
  }

  /** 作業の中断と再開で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void onlyOldFailuresAreFilteredButUnknownOperationsRemain() throws Exception {
    save("failed", "FAILED", 0);
    save("waiting", "WAITING", 0);
    save("approval", "WAITING_APPROVAL", 0);
    save("cancelled", "CANCELLED", 0);
    save("abandoned", "ABANDONED", 0);
    var done = save("unknown", "COMPLETED", 0);
    var f = repo.fields(done);
    f.put(
        "operations",
        List.of(
            new PersistentCheckpoint.Operation(
                "call", "tool", PersistentCheckpoint.OperationStatus.UNKNOWN, "event")));
    repo.save(repo.fields(f), 1, "unknown-op");
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var s = c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET created='2000-01-01T00:00:00Z'");
    }
    assertEquals(
        Set.of("waiting", "approval", "unknown"),
        new HashSet<>(
            repo.overviewCandidates("A", Instant.now().minus(Duration.ofDays(30))).stream()
                .map(ResumeOverview.Candidate::taskId)
                .toList()));
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void corruptionFallsBackWithoutPretendingToBeLatest() throws Exception {
    save("task", "INTERRUPTED", 0);
    save("task", "COMPLETED", 1);
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var s = c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET snapshot='broken' WHERE revision=2");
    }
    var candidate = repo.overviewCandidates("A", Instant.EPOCH).getFirst();
    assertTrue(candidate.fallback());
    assertEquals(1, candidate.revision());
    assertEquals(2, candidate.headRevision());
    assertTrue(candidate.notice().contains("参考"));
  }

  /** 作業の中断と再開で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void countAndLatestQueryUsePrimaryKeyIndexesAndLeaveDatabaseUntouched() throws Exception {
    for (int i = 0; i < 20; i++) {
      save("task-" + i, "INTERRUPTED", 0);
      save("task-" + i, "INTERRUPTED", 1);
    }
    var before = repo.historyUsage();
    var selected = repo.overviewSelection("A", Instant.EPOCH);
    assertEquals(20, selected.count());
    assertNotNull(selected.latest());
    assertEquals(before, repo.historyUsage());
    var plan = repo.overviewQueryPlan("A", Instant.EPOCH);
    System.out.println("Resume overview query plan: " + plan);
    assertTrue(
        plan.stream()
            .anyMatch(line -> line.contains("SEARCH h USING INDEX") && line.contains("project=?")));
    assertTrue(
        plan.stream()
            .anyMatch(
                line -> line.contains("SEARCH r USING INDEX") && line.contains("revision=?")));
    assertFalse(plan.stream().anyMatch(line -> line.equals("SCAN r")));
  }

  /** 一致について、時刻一覧利用タスク識別情報を保存することを確認する。 */
  @Test
  void equalSaveTimesUseTaskIdentityAndFractionalTimeOrderingIsExact() throws Exception {
    save("b", "INTERRUPTED", 0);
    save("a", "INTERRUPTED", 0);
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var s = c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET created='2026-10-13T00:00:00Z'");
      assertEquals("a", repo.overviewSelection("A", Instant.EPOCH).latest().taskId());
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET created='2026-10-13T00:00:00.000000001Z' WHERE task='b'");
      assertEquals("b", repo.overviewSelection("A", Instant.EPOCH).latest().taskId());
    }
  }

  /** 作業の中断と再開で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void identityMismatchFallsBackAndCompletelyBrokenTaskNeedsReview() throws Exception {
    save("task", "WAITING", 0);
    save("task", "COMPLETED", 1);
    save("broken", "INTERRUPTED", 0);
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var s = c.createStatement()) {
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET snapshot=json_set(snapshot,'$.taskId','wrong') WHERE task='task' AND revision=2");
      s.executeUpdate("UPDATE checkpoint_revisions SET snapshot='broken' WHERE task='broken'");
    }
    var rows = repo.overviewCandidates("A", Instant.EPOCH);
    assertEquals(2, rows.size());
    assertTrue(rows.stream().allMatch(ResumeOverview.Candidate::fallback));
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void representativeHistoryProjectsOnlyOneCandidateWithoutJavaSnapshotReads() throws Exception {
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var head =
            c.prepareStatement(
                "INSERT INTO checkpoint_heads(project,task,revision) VALUES('A',?,5)");
        var revision =
            c.prepareStatement(
                "INSERT INTO checkpoint_revisions(project,task,revision,event,snapshot,created) VALUES('A',?,?,?,?,?)")) {
      c.setAutoCommit(false);
      for (int task = 0; task < 1000; task++) {
        String id = String.format(Locale.ROOT, "task-%04d", task);
        head.setString(1, id);
        head.executeUpdate();
        var initial =
            PersistentCheckpoint.initial(
                id, "A", "s", "r", directory, "request " + "x".repeat(4096));
        var fields = repo.fields(initial);
        fields.put("status", "INTERRUPTED");
        for (int r = 1; r <= 5; r++) {
          fields.put("revision", r);
          revision.setString(1, id);
          revision.setInt(2, r);
          revision.setString(3, "event-" + r);
          revision.setString(4, repo.encode(repo.fields(fields)));
          revision.setString(5, "2026-10-13T00:00:00Z");
          revision.executeUpdate();
        }
      }
      c.commit();
    }
    var observed = org.mockito.Mockito.spy(repo);
    var timings = new ArrayList<Long>();
    for (int i = 0; i < 5; i++) {
      long start = System.nanoTime();
      var selected = observed.overviewSelection("A", Instant.EPOCH);
      timings.add(System.nanoTime() - start);
      assertEquals(1000, selected.count());
      assertEquals("task-0000", selected.latest().taskId());
    }
    org.mockito.Mockito.verify(observed, org.mockito.Mockito.never())
        .get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    Collections.sort(timings);
    System.out.println(
        "Resume overview SQLite: tasks=1000 revisions=5000 request_chars=4104 median_ms="
            + timings.get(2) / 1_000_000.0);
  }

  /** 作業の中断と再開で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureCutoffIsInclusiveAndDoesNotHideUnknownFailures() throws Exception {
    save("boundary", "FAILED", 0);
    save("expired", "FAILED", 0);
    save("unknown-failure", "FAILED", 0);
    var state = repo.get("A", "unknown-failure");
    var fields = repo.fields(state);
    fields.put(
        "operations",
        List.of(
            new PersistentCheckpoint.Operation(
                "c", "tool", PersistentCheckpoint.OperationStatus.UNKNOWN, "e")));
    repo.save(repo.fields(fields), 1, "unknown");
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var s = c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET created='2026-09-13T00:00:00Z'");
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET created='2026-09-12T23:59:59.999999999Z' WHERE task='expired'");
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET created='2000-01-01T00:00:00Z' WHERE task='unknown-failure'");
    }
    assertEquals(
        Set.of("boundary", "unknown-failure"),
        new HashSet<>(
            repo.overviewCandidates("A", Instant.parse("2026-09-13T00:00:00Z")).stream()
                .map(ResumeOverview.Candidate::taskId)
                .toList()));
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void completionUsesRecordedPlanAndClaimsButNeverRawToolOutput() {
    var state = save("task", "INTERRUPTED", 0);
    var fields = repo.fields(state);
    fields.put(
        "evidence",
        List.of(
            new PersistentCheckpoint.Evidence(
                "TOOL_CONFIRMED", "raw tool output", null, null, null)));
    repo.save(repo.fields(fields), 1, "raw");
    assertEquals("未記録", repo.overviewSelection("A", Instant.EPOCH).latest().completed());
    fields.put(
        "plan",
        List.of(
            Map.of(
                "id",
                "step",
                "description",
                "recorded completion",
                "status",
                "DONE",
                "order",
                1,
                "failureCount",
                0)));
    fields.put("nextAction", "recorded pending");
    repo.save(repo.fields(fields), 2, "plan");
    var candidate = repo.overviewSelection("A", Instant.EPOCH).latest();
    assertEquals("recorded completion", candidate.completed());
    assertEquals("recorded pending", candidate.remaining());
    fields.put("plan", List.of());
    fields.put(
        "evidence",
        List.of(
            new PersistentCheckpoint.Evidence(
                "ASSISTANT_CLAIM", "claimed completion", null, null, null)));
    repo.save(repo.fields(fields), 3, "claim");
    assertEquals(
        "claimed completion（保存された申告）",
        repo.overviewSelection("A", Instant.EPOCH).latest().completed());
  }

  /** 作業の中断と再開で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void unsupportedSchemaAndNullCollectionFallBackToTheExistingDecoder() throws Exception {
    save("schema", "WAITING_APPROVAL", 0);
    save("schema", "COMPLETED", 1);
    save("null", "INTERRUPTED", 0);
    save("null", "COMPLETED", 1);
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("overview.db"));
        var s = c.createStatement()) {
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET snapshot=json_set(snapshot,'$.schemaVersion',2) WHERE task='schema' AND revision=2");
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET snapshot=json_set(snapshot,'$.operations',null) WHERE task='null' AND revision=2");
    }
    var rows = repo.overviewCandidates("A", Instant.EPOCH);
    assertEquals(2, rows.size());
    assertTrue(rows.stream().allMatch(c -> c.fallback() && c.revision() == 1));
  }
}
