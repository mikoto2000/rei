package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保存保守の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class StorageMaintenanceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う登録台帳。 */
  StorageObjectRegistry registry;

  /** 検証に使う保守。 */
  StorageMaintenance maintenance;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void prepare() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    registry = new StorageObjectRegistry(root);
    maintenance = new StorageMaintenance(registry);
  }

  /** 保存領域の整理について、会話操作一覧を受け付けないことを確認する。 */
  @Test
  void vacuumRejectsSessionOperationsWithoutTurnsUntilTheirExecutionLeaseIsReleased() {
    try (var admissions =
        new dev.mikoto2000.rei.conversation.ConversationAdmissionStore(root, Clock.systemUTC())) {
      String project = java.util.UUID.randomUUID().toString(), session = "operation-session";
      Instant now = Instant.now();
      var context =
          new dev.mikoto2000.rei.core.chat.AgentRunContext(
              "operation-run",
              session,
              root,
              project,
              dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB);
      admissions.accept(
          new dev.mikoto2000.rei.application.session.SessionMetadata(
              session, project, "memory maintenance", now, now),
          context,
          null,
          null);
      admissions.startExecution(context);
      assertThatThrownBy(() -> maintenance.vacuum(Duration.ofSeconds(30)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Active Run");
      admissions.transition(
          context.runId(), dev.mikoto2000.rei.application.run.RunStatus.COMPLETED);
      assertThatThrownBy(() -> maintenance.vacuum(Duration.ofSeconds(30)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Active Run");
      admissions.release(context.runId());
      maintenance.vacuum(Duration.ofSeconds(30));
    }
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void observationIsReadOnlyAndIncrementalVacuumDoesNotChangeLegacyMode() throws Exception {
    var before = maintenance.status();
    assertThat(before.databaseBytes()).isPositive();
    assertThat(before.pageSize()).isPositive();
    assertThat(before.usableDiskBytes()).isPositive();
    assertThat(maintenance.incrementalVacuum(100)).isFalse();
    assertThat(maintenance.status().autoVacuum()).isEqualTo(before.autoVacuum());
    assertThatThrownBy(() -> maintenance.incrementalVacuum(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** データ保存の共通処理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void passiveCheckpointReportsAnOngoingReaderAndVacuumCanReclaimFreePagesInExplicitWindow()
      throws Exception {
    registry.database.transaction(
        db -> {
          try (var query = db.createStatement()) {
            query.execute("CREATE TABLE maintenance_fixture(value BLOB)");
            query.execute("INSERT INTO maintenance_fixture VALUES(zeroblob(4194304))");
          }
          return null;
        });
    try (var reader = StorageBackup.readOnly(root.resolve("storage.db"));
        var query = reader.createStatement();
        var rows = query.executeQuery("SELECT value FROM maintenance_fixture")) {
      assertThat(rows.next()).isTrue();
      registry.database.transaction(
          db -> {
            try (var write = db.createStatement()) {
              write.execute("DELETE FROM maintenance_fixture");
            }
            return null;
          });
      var checkpoint = maintenance.checkpoint();
      assertThat(checkpoint.logFrames()).isGreaterThan(checkpoint.checkpointedFrames());
      assertThatThrownBy(() -> maintenance.vacuum(Duration.ofSeconds(30)))
          .isInstanceOf(IllegalStateException.class);
    }
    var before = maintenance.status();
    assertThat(before.freePages()).isPositive();
    maintenance.vacuum(Duration.ofSeconds(30));
    assertThat(maintenance.status().pageCount()).isLessThan(before.pageCount());
  }

  /** データ保存の共通処理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void incrementalModeIsPreservedAndReclaimsOnlyABoundedBatch() throws Exception {
    Path separate = Files.createDirectories(root.resolve("incremental-fixture"));
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + separate.resolve("storage.db"));
        var query = db.createStatement()) {
      query.execute("PRAGMA auto_vacuum=INCREMENTAL");
    }
    try (var gate = new StorageMigrationCoordinator(separate)) {
      gate.prepare();
    }
    var objects = new StorageObjectRegistry(separate);
    var maintenance = new StorageMaintenance(objects);
    objects.database.transaction(
        db -> {
          try (var query = db.createStatement()) {
            query.execute("CREATE TABLE free_fixture(value BLOB)");
            query.execute("INSERT INTO free_fixture VALUES(zeroblob(4194304))");
            query.execute("DELETE FROM free_fixture");
          }
          return null;
        });
    var before = maintenance.status();
    assertThat(before.autoVacuum()).isEqualTo(2);
    assertThat(maintenance.incrementalVacuum(10)).isTrue();
    var after = maintenance.status();
    assertThat(after.autoVacuum()).isEqualTo(2);
    assertThat(before.freePages() - after.freePages()).isBetween(1L, 10L);
  }
}
