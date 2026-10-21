package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 自動保持期間の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class AutomaticRetentionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final RetentionExecutionTest.MutableClock clock = new RetentionExecutionTest.MutableClock();

  /** 検証に使う登録台帳。 */
  StorageObjectRegistry registry;

  /** 検証に使う計画の作成。 */
  RetentionPlanner planner;

  /** 検証に使う自動。 */
  AutomaticRetention automatic;

  /** 検証に使う実行先。 */
  RetentionExecutor executor;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void prepare() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    registry = new StorageObjectRegistry(root, clock);
    planner = new RetentionPlanner(registry);
    executor = new RetentionExecutor(registry, planner);
    automatic = new AutomaticRetention(registry, planner, executor);
  }

  /** {@code archive} の条件を再現するための補助情報を返す。 */
  Path archive(String project) throws Exception {
    Path file =
        (project == null ? root : root.resolve("projects").resolve(project))
            .resolve("logs/activity-archives/" + UUID.randomUUID() + ".jsonl");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "old diagnostic\n");
    registry.registerProducedFile(
        file,
        "ACTIVITY_RAW",
        project,
        null,
        clock.instant().minus(Duration.ofDays(100)),
        true,
        List.of());
    return file;
  }

  /** データ保存の共通処理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void defaultOffAndProposalWithoutApprovalNeverRunsAndApprovedScopeHasFixedBudget()
      throws Exception {
    Path global = archive(null);
    String project = UUID.randomUUID().toString();
    Path one = archive(project), two = archive(project);
    automatic.tick();
    assertThat(global).exists();
    assertThat(one).exists();
    var consent = automatic.propose("activity-raw", project, 1, 1024, Duration.ofMinutes(10));
    automatic.tick();
    assertThat(one).exists();
    assertThat(two).exists();
    assertThat(consent.policy().retentionSeconds()).isEqualTo(Duration.ofDays(90).getSeconds());
    automatic.approve(consent.id());
    automatic.tick();
    assertThat(executor.history()).hasSize(1);
    assertThat(Files.exists(one) && Files.exists(two)).isFalse();
    assertThat(Files.exists(one) || Files.exists(two)).isTrue();
    assertThat(global).exists();
    automatic.tick();
    assertThat(executor.history()).hasSize(1);
    clock.time = clock.time.plus(Duration.ofMinutes(11));
    automatic.tick();
    assertThat(executor.history()).hasSize(2);
    assertThat(executor.history())
        .allSatisfy(execution -> assertThat(execution.status()).isEqualTo("QUARANTINED"));
    automatic.disable(consent.id());
    clock.time = clock.time.plus(Duration.ofDays(100));
    automatic.tick();
    assertThat(executor.history()).hasSize(2);
  }

  /** データ保存の共通処理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void changingPolicyInvalidatesProposalAndExistingConsent() throws Exception {
    var stale = automatic.propose("activity-raw", null, 1, 1024, Duration.ofMinutes(10));
    planner.updatePolicy("activity-raw", Duration.ofDays(100), null, null);
    assertThatThrownBy(() -> automatic.approve(stale.id()))
        .isInstanceOf(IllegalStateException.class);
    var approved = automatic.propose("activity-raw", null, 1, 1024, Duration.ofMinutes(10));
    automatic.approve(approved.id());
    Path file = archive(null);
    planner.updatePolicy("activity-raw", Duration.ofDays(101), null, null);
    automatic.tick();
    assertThat(file).exists();
    assertThat(executor.history()).isEmpty();
  }
}
