package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保存保有情報の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class StorageInventoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void measuresUtf8RecordsWithoutChangingFiles() throws Exception {
    Files.createDirectories(root.resolve("events"));
    var file = root.resolve("events/events.jsonl");
    String text = "{\"message\":\"れい\"}\n{\"message\":\"二\"}\n";
    Files.writeString(file, text);
    var before = Files.getLastModifiedTime(file);
    var report = new StorageInventory(Clock.systemUTC(), 100, 1024).measure(root);
    var row = report.categories().get("events");
    assertThat(row.files()).isEqualTo(1);
    assertThat(row.records()).isEqualTo(2);
    assertThat(row.physicalBytes())
        .isEqualTo(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    assertThat(Files.readString(file)).isEqualTo(text);
    assertThat(Files.getLastModifiedTime(file)).isEqualTo(before);
    assertThat(report.unknown())
        .isNotEmpty(); // References have not been validated by an inventory.
  }

  /** 未完了のを伝えることを確認する。 */
  @Test
  void reportsIncompleteAndUnscannedRecordsInsteadOfInventingCounts() throws Exception {
    Files.createDirectories(root.resolve("events"));
    Files.writeString(root.resolve("events/events.jsonl"), "{}\n{\"unfinished\":");
    var report = new StorageInventory(Clock.systemUTC(), 100, 1024).measure(root);
    assertThat(report.categories().get("events").records()).isNull();
    assertThat(report.unknown()).anyMatch(s -> s.contains("incomplete"));
    var bounded = new StorageInventory(Clock.systemUTC(), 100, 2).measure(root);
    assertThat(bounded.categories().get("events").records()).isNull();
    assertThat(bounded.unknown()).anyMatch(s -> s.contains("budget"));
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingRootDoesNotCreateData() {
    Path missing = root.resolve("missing");
    assertThat(new StorageInventory(Clock.systemUTC(), 100, 1024).measure(missing).categories())
        .isEmpty();
    assertThat(missing).doesNotExist();
  }

  /** データ保存の共通処理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void migrationBackupsAreSeparateFromLiveSessionCounts() throws Exception {
    Files.writeString(root.resolve("sessions.json"), "[{}]");
    Path backup = Files.createDirectories(root.resolve(".storage/backups/example/files"));
    Files.writeString(backup.resolve("sessions.json"), "[{},{}]");
    var report = new StorageInventory(Clock.systemUTC(), 100, 1024).measure(root);
    assertThat(report.categories().get("sessions").records()).isEqualTo(1);
    assertThat(report.categories().get("migration-backups").files()).isEqualTo(1);
  }

  /** データ保存の共通処理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void completeJsonWithoutTerminatingNewlineIsNotACommittedReplayRecord() throws Exception {
    Files.createDirectories(root.resolve("events"));
    Files.writeString(root.resolve("events/events.jsonl"), "{}");
    assertThat(
            new StorageInventory(Clock.systemUTC(), 100, 1024)
                .measure(root)
                .categories()
                .get("events")
                .records())
        .isNull();
  }

  /** 前回計測値を利用することを確認する。 */
  @Test
  void growthUsesPreviousMeasurementForTheSameRootOnly() throws Exception {
    var inventory = new StorageInventory(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 100, 1024);
    Files.writeString(root.resolve("unknown.bin"), "a");
    assertThat(inventory.measure(root).deltaBytes()).isNull();
    Files.writeString(root.resolve("unknown.bin"), "abc");
    assertThat(inventory.measure(root).deltaBytes()).isEqualTo(2);
    assertThat(inventory.measure(root).bytesPerSecond()).isNull(); // No elapsed time.
    assertThat(inventory.measure(root.resolve("other")).deltaBytes()).isNull();
  }
}
