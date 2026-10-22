package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 永続化された補助エージェント保存と検索の窓口の結果と状態の契約を検証するテスト。 */
class DurableSubAgentRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う取得元。 */
  SQLiteDataSource source;

  /** 検証に使う親。 */
  AgentRunContext parent;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("children.db"));
    parent =
        new AgentRunContext(
            "parent", "session", Files.createDirectory(directory.resolve("project")), "project");
  }

  /** 別接続の書き込み中でも読み取りロックの昇格で即時失敗せず、解放後に保存できる。 */
  @Test
  void checkpointWaitsForWriterBeforeReadingInBothJournalModes() throws Exception {
    for (String mode : java.util.List.of("DELETE", "WAL")) {
      try (var blocker = source.getConnection();
          var statement = blocker.createStatement()) {
        try (var rows = statement.executeQuery("PRAGMA journal_mode=" + mode)) {
          assertTrue(rows.next());
          assertEquals(mode, rows.getString(1).toUpperCase(java.util.Locale.ROOT));
        }
        var begun =
            new java.util.concurrent.atomic.AtomicReference<>(
                new java.util.concurrent.CountDownLatch(1));
        var observed =
            new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
              @Override
              public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                return (java.sql.Connection)
                    java.lang.reflect.Proxy.newProxyInstance(
                        java.sql.Connection.class.getClassLoader(),
                        new Class<?>[] {java.sql.Connection.class},
                        (proxy, method, args) -> {
                          try {
                            Object result = method.invoke(connection, args);
                            if (method.getName().equals("setAutoCommit")
                                && Boolean.FALSE.equals(args[0])) begun.get().countDown();
                            return result;
                          } catch (java.lang.reflect.InvocationTargetException error) {
                            throw error.getCause();
                          }
                        });
              }
            };
        var repository = new DurableSubAgentRepository(observed, Clock.systemUTC());
        var saved = repository.create(parent, "reviewer", "inspect", null, 3, 1000, "baseline");
        // create also begins a transaction; only observe the subsequent claim.
        begun.set(new java.util.concurrent.CountDownLatch(1));
        blocker.setAutoCommit(false);
        statement.executeUpdate("UPDATE subagent_checkpoints SET revision=revision WHERE 0");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
          var pending = executor.submit(
              () -> repository.claim(parent, saved.id(), saved.revision(), "child-" + mode));
          try {
            assertTrue(begun.get().await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(
                java.util.concurrent.TimeoutException.class,
                () -> pending.get(300, java.util.concurrent.TimeUnit.MILLISECONDS));
          } finally {
            blocker.commit();
          }
          assertEquals("RUNNING", pending.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
        } finally {
          blocker.setAutoCommit(true);
        }
      }
    }
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void graphAdmissionRollsBackAllPreparedChildrenWhenCapacityIsExceeded() {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC(), 2048);
    String graph = UUID.randomUUID().toString();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.atomic(
                () -> {
                  repository.createPrepared(
                      parent, graph, "GRAPH", null, "rei-dag", "graph", null, 4, 0, "baseline");
                  repository.createPrepared(
                      parent,
                      UUID.randomUUID().toString(),
                      "CHILD",
                      graph,
                      "reviewer",
                      "inspect",
                      "x".repeat(2000),
                      2,
                      0,
                      "baseline");
                  return null;
                }));
    assertTrue(repository.list(parent, 0, 10).isEmpty());
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void completedResultAndConsumedBudgetSurviveRepositoryRestartAndRemainOwnerScoped()
      throws Exception {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    var saved = repository.create(parent, "reviewer", "inspect A", null, 3, 1000, "baseline");
    var started = repository.claim(parent, saved.id(), saved.revision(), "child-run");
    assertTrue(repository.reserve(started.id(), "child-run"));
    repository.tokens(started.id(), "child-run", 100);
    repository.complete(started.id(), "child-run", "COMPLETED", "verified result");
    var restored = new DurableSubAgentRepository(source, Clock.systemUTC()).get(parent, saved.id());
    assertEquals("COMPLETED", restored.status());
    assertEquals(1, restored.consumedCalls());
    assertEquals(100, restored.consumedTokens());
    assertEquals("verified result", restored.result());
    assertEquals(64, restored.resultHash().length());
    var foreign =
        new AgentRunContext("other", "other-session", parent.projectRoot(), parent.projectId());
    assertThrows(IllegalArgumentException.class, () -> repository.get(foreign, saved.id()));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.claim(parent, saved.id(), restored.revision(), "new-run"));
  }

  /** 失われた所有者および待機中ツールについて、明示的な状態の照合結果を必要とすることを確認する。 */
  @Test
  void lostOwnerAndPendingToolRequireExplicitReconciliationWithoutResettingBudget()
      throws Exception {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    var saved = repository.create(parent, "reviewer", "inspect", null, 2, 1000, "baseline");
    repository.claim(parent, saved.id(), saved.revision(), "child-run");
    assertTrue(repository.reserve(saved.id(), "child-run"));
    repository.tokens(saved.id(), "child-run", 100);
    repository.toolStarted(saved.id(), "child-run", "operation", "writeFile", "args-hash");
    repository.ownerLost(saved.id(), "child-run");
    var restored = new DurableSubAgentRepository(source, Clock.systemUTC()).get(parent, saved.id());
    assertEquals("UNKNOWN", restored.status());
    assertEquals("UNKNOWN", restored.operations().getFirst().status());
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.claim(parent, saved.id(), restored.revision(), "next"));
    var reconciled =
        repository.reconcile(
            parent,
            saved.id(),
            restored.revision(),
            "operation",
            "SUCCEEDED",
            "human checked the file");
    var resumed = repository.claim(parent, saved.id(), reconciled.revision(), "next");
    assertEquals(1, resumed.consumedCalls());
    assertTrue(repository.reserve(saved.id(), "next"));
    assertFalse(repository.reserve(saved.id(), "next"));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.toolStarted(saved.id(), "child-run", "stale", "readFile", "hash"));
    repository.tokens(saved.id(), "next", null);
    assertFalse(repository.reserve(saved.id(), "next"));
    assertTrue(repository.get(parent, saved.id()).usageUnknown());
  }

  /** 補助エージェントへの委譲で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void revisionsPreventDuplicateClaimsAndReadOnlyRunsCannotReconcile() {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    var saved = repository.create(parent, "reviewer", "inspect", null, 3, 0, "baseline");
    var other = new DurableSubAgentRepository(source, Clock.systemUTC());
    repository.claim(parent, saved.id(), 0, "first");
    assertThrows(
        IllegalArgumentException.class, () -> other.claim(parent, saved.id(), 0, "second"));
    repository.toolStarted(saved.id(), "first", "operation", "readFile", "hash");
    repository.ownerLost(saved.id(), "first");
    var unknown = repository.get(parent, saved.id());
    var reader =
        new AgentRunContext(
            "read",
            "session",
            parent.projectRoot(),
            parent.projectId(),
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.READ_ONLY);
    assertEquals(unknown, repository.get(reader, saved.id()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.reconcile(
                reader, saved.id(), unknown.revision(), "operation", "FAILED", "human checked"));
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void startupLossDuringAnUnreportedModelCallBlocksTokenLimitedResume() throws Exception {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    var saved = repository.create(parent, "reviewer", "inspect", null, 3, 1000, "baseline");
    repository.claim(parent, saved.id(), 0, "lost");
    assertTrue(repository.reserve(saved.id(), "lost"));
    var db = org.springframework.jdbc.core.simple.JdbcClient.create(source);
    String encoded =
        db.sql("SELECT snapshot FROM subagent_checkpoints WHERE id=?")
            .param(saved.id())
            .query(String.class)
            .single();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(encoded);
    document.put("ownerPid", -1);
    db.sql("UPDATE subagent_checkpoints SET snapshot=? WHERE id=?")
        .params(json.writeValueAsString(document), saved.id())
        .update();
    var restored = new DurableSubAgentRepository(source, Clock.systemUTC()).get(parent, saved.id());
    assertEquals("UNKNOWN", restored.status());
    assertTrue(restored.usageUnknown());
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.claim(parent, saved.id(), restored.revision(), "retry"));
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void storageQuotaAppliesToNewTasksAndGrowingResultsWithoutLosingThePreviousCheckpoint() {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC(), 4096);
    var saved = repository.create(parent, "reviewer", "a".repeat(2000), null, 3, 0, "baseline");
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.create(parent, "reviewer", "b".repeat(2000), null, 3, 0, "baseline"));
    var started = repository.claim(parent, saved.id(), 0, "run");
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.complete(saved.id(), "run", "COMPLETED", "c".repeat(4000)));
    assertEquals(started, repository.get(parent, saved.id()));
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void zeroUsageCannotMakeATokenLimitedReservationKnown() {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    var saved = repository.create(parent, "reviewer", "inspect", null, 3, 1000, "baseline");
    repository.claim(parent, saved.id(), 0, "run");
    assertTrue(repository.reserve(saved.id(), "run"));
    repository.tokens(saved.id(), "run", 0);
    assertTrue(repository.get(parent, saved.id()).usageUnknown());
    assertFalse(repository.reserve(saved.id(), "run"));
  }

  /** 補助エージェントへの委譲で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void parallelReservationsRemainPendingUntilEveryUsageReportArrives() {
    var repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    var saved = repository.create(parent, "reviewer", "inspect", null, 4, 1000, "baseline");
    repository.claim(parent, saved.id(), 0, "run");
    assertTrue(repository.reserve(saved.id(), "run"));
    assertTrue(repository.reserve(saved.id(), "run"));
    repository.tokens(saved.id(), "run", 100);
    repository.ownerLost(saved.id(), "run");
    var unknown = repository.get(parent, saved.id());
    assertEquals(2, unknown.consumedCalls());
    assertTrue(unknown.usageUnknown());
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.claim(parent, saved.id(), unknown.revision(), "retry"));
  }
}
