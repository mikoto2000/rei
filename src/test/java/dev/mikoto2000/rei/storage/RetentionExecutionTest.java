package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保持期間実行の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class RetentionExecutionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証条件を再現するための変更可能な時刻の取得元。 */
  static final class MutableClock extends Clock {
    /** 検証に使う時刻。 */
    Instant time = Instant.parse("2026-10-10T00:00:00Z");

    /** 検証用に、データ保存の共通処理で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId zone) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return time;
    }
  }

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final MutableClock clock = new MutableClock();

  /** 検証に使う登録台帳。 */
  StorageObjectRegistry registry;

  /** 検証に使う計画の作成。 */
  RetentionPlanner planner;

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
  }

  /** {@code archive} の条件を再現するための補助情報を返す。 */
  StorageObjectRegistry.StoredObject archive() throws Exception {
    Path file = root.resolve("logs/activity-archives/" + UUID.randomUUID() + ".jsonl");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "{\"diagnostic\":true}\n");
    return registry.registerProducedFile(
        file,
        "ACTIVITY_RAW",
        null,
        null,
        clock.instant().minus(Duration.ofDays(100)),
        true,
        List.of());
  }

  /** {@code plan} の条件を再現するための補助情報を返す。 */
  RetentionPlanner.Plan plan() {
    return planner.plan("activity-raw", null, 10, 1024 * 1024);
  }

  /** データ保存の共通処理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void approvalIsRequiredAndExactBodyCanBeQuarantinedAndRestored() throws Exception {
    var body = archive();
    var plan = plan();
    Path original = root.resolve(body.relativePath());
    String hash = StorageBackup.hash(original);
    assertThatThrownBy(() -> executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);
    assertThat(original).exists();
    planner.approve(plan.id());
    var execution = executor.apply(plan.id());
    assertThat(original).doesNotExist();
    assertThat(execution.status()).isEqualTo("QUARANTINED");
    assertThat(executor.apply(plan.id()).id()).isEqualTo(execution.id());
    assertThatThrownBy(() -> registry.addReference(body.id(), "CHECKPOINT", "new-owner"))
        .isInstanceOf(IllegalStateException.class);
    executor.restore(execution.id());
    assertThat(StorageBackup.hash(original)).isEqualTo(hash);
    assertThat(registry.protectionReason(body)).isEmpty();
    registry.addReference(body.id(), "CHECKPOINT", "restored-owner");
    assertThat(plan().candidates()).isEmpty();
  }

  /** データ保存の共通処理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void newPinAfterApprovalPreventsAnyMove() throws Exception {
    var body = archive();
    var plan = plan();
    planner.approve(plan.id());
    registry.pin(body.id(), true);
    assertThatThrownBy(() -> executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);
    assertThat(root.resolve(body.relativePath())).exists();
  }

  /** データ保存の共通処理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void purgeNeedsSeparateApprovalAndGraceAndPreservesHeldBodies() throws Exception {
    var body = archive();
    var plan = plan();
    planner.approve(plan.id());
    var execution = executor.apply(plan.id());
    assertThatThrownBy(() -> executor.approvePurge(execution.id()))
        .isInstanceOf(IllegalStateException.class);
    clock.time = clock.time.plus(Duration.ofDays(8));
    assertThatThrownBy(() -> executor.purge(execution.id()))
        .isInstanceOf(IllegalStateException.class);
    executor.approvePurge(execution.id());
    registry.legalHold(body.id(), true);
    assertThatThrownBy(() -> executor.purge(execution.id()))
        .isInstanceOf(IllegalStateException.class);
    registry.legalHold(body.id(), false);
    executor.approvePurge(execution.id());
    executor.purge(execution.id());
    assertThat(executor.get(execution.id()).status()).isEqualTo("DELETED");
    assertThatThrownBy(() -> executor.restore(execution.id()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(planner.status(null).objects())
        .allSatisfy(usage -> assertThat(usage.recordedBodyBytes()).isZero());
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void restorationNeverOverwritesAnExistingOriginal() throws Exception {
    var body = archive();
    var plan = plan();
    planner.approve(plan.id());
    var execution = executor.apply(plan.id());
    Path original = root.resolve(body.relativePath());
    Files.writeString(original, "new content");
    assertThatThrownBy(() -> executor.restore(execution.id()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Files.readString(original)).isEqualTo("new content");
  }

  /** データ保存の共通処理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void changedBodyAfterApprovalRemainsAtOriginalPath() throws Exception {
    var body = archive();
    var plan = plan();
    planner.approve(plan.id());
    Path original = root.resolve(body.relativePath());
    Files.writeString(original, "changed");
    assertThatThrownBy(() -> executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);
    assertThat(Files.readString(original)).isEqualTo("changed");
  }

  /** データ保存の共通処理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void policyChangeStopsAnInterruptedApplyAndLeavesRestorationAvailable() throws Exception {
    var body = archive();
    var plan = plan();
    planner.approve(plan.id());
    var stopped =
        new RetentionExecutor(
            registry,
            planner,
            (stage, id) -> {
              if (stage.equals("DELETING")) throw new IllegalStateException("fixture interruption");
            });
    assertThatThrownBy(() -> stopped.apply(plan.id())).isInstanceOf(IllegalStateException.class);
    planner.updatePolicy("activity-raw", Duration.ofDays(365), null, null);
    assertThatThrownBy(() -> executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);
    assertThat(root.resolve(body.relativePath())).exists();
    executor.restore(executor.history().getFirst().id());
    assertThat(root.resolve(body.relativePath())).exists();
  }

  /** データ保存の共通処理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void changedPolicyInvalidatesExistingPurgeApproval() throws Exception {
    var body = archive();
    var plan = plan();
    planner.approve(plan.id());
    var execution = executor.apply(plan.id());
    clock.time = clock.time.plus(Duration.ofDays(8));
    executor.approvePurge(execution.id());
    planner.updatePolicy("activity-raw", Duration.ofDays(365), null, null);
    assertThatThrownBy(() -> executor.purge(execution.id()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(executor.get(execution.id()).status()).isEqualTo("QUARANTINED");
    executor.restore(execution.id());
    assertThat(root.resolve(body.relativePath())).exists();
  }

  /** データ保存の共通処理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"apply", "restore", "purge"})
  void processKillAfterFileMutationCanResumeFromDurableJournal(String action) throws Exception {
    var body = archive();
    var plan = plan();
    planner.approve(plan.id());
    String target = plan.id(), stage = "MOVED", execution = null;
    if (!action.equals("apply")) {
      execution = executor.apply(plan.id()).id();
      target = execution;
      stage = action.equals("restore") ? "RESTORED_FILE" : "PURGED_FILE";
    }
    if (action.equals("purge")) {
      clock.time = clock.time.plus(Duration.ofDays(8));
      executor.approvePurge(execution);
    }
    Path arguments = root.resolve("crash.args");
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Files.writeString(
        arguments,
        "-cp\n"
            + arg(classpath)
            + "\n"
            + RetentionCrashWorker.class.getName()
            + "\n"
            + arg(root.toString())
            + "\n"
            + action
            + "\n"
            + target
            + "\n"
            + stage
            + "\n"
            + clock.instant()
            + "\n");
    Path output = root.resolve("crash.log");
    var child =
        new ProcessBuilder(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                    .toString(),
                "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
      while (child.isAlive()
          && System.nanoTime() < deadline
          && !Files.readString(output).contains("PAUSED")) Thread.sleep(50);
      assertThat(Files.readString(output)).contains("PAUSED");
      assertThatThrownBy(() -> new StorageMigrationCoordinator(root))
          .isInstanceOf(java.io.IOException.class);
    } finally {
      child.destroyForcibly();
      assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    executor = new RetentionExecutor(registry, planner);
    if (action.equals("apply")) {
      var result = executor.apply(plan.id());
      assertThat(result.status()).isEqualTo("QUARANTINED");
      executor.restore(result.id());
    }
    if (action.equals("restore"))
      assertThat(executor.restore(target).status()).isEqualTo("RESTORED");
    if (action.equals("purge")) {
      registry.legalHold(body.id(), true);
      String executionId = target;
      assertThatThrownBy(() -> executor.purge(executionId))
          .isInstanceOf(IllegalStateException.class);
      registry.legalHold(body.id(), false);
      assertThatThrownBy(() -> executor.purge(executionId))
          .isInstanceOf(IllegalStateException.class);
      executor.approvePurge(target);
      assertThat(executor.purge(target).status()).isEqualTo("DELETED");
    }
    assertThat(Files.exists(root.resolve(body.relativePath()))).isEqualTo(!action.equals("purge"));
  }

  /** {@code arg} の条件を再現するための補助情報を返す。 */
  private static String arg(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
