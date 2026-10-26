package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import dev.mikoto2000.rei.event.ProfileEventLogEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 管理対象作業状況ログの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ManagedActivityLogTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う登録台帳。 */
  StorageObjectRegistry registry;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void prepare() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    registry =
        new StorageObjectRegistry(
            root, Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC));
  }

  /** {@code entry} の条件を再現するための補助情報を返す。 */
  ProfileEventLogEntry entry(String id, String session, String run) {
    return new ProfileEventLogEntry(
        id,
        1,
        registry.clock.instant(),
        "diagnostic",
        1,
        session,
        null,
        run,
        null,
        null,
        null,
        Map.of("日本語", "保持"));
  }

  @Test
  void cancelledCallerCanPersistItsTerminalEventAndNextRunCanAppend() {
    var activity = new ManagedActivityLog(registry);
    activity.append(null, entry("before", "session", "cancelled-run"));
    Thread.currentThread().interrupt();
    try {
      activity.append(null, entry("cancelled", "session", "cancelled-run"));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    activity.append(null, entry("next", "session", "next-run"));
    assertThat(activity.read(null))
        .extracting(ProfileEventLogEntry::id)
        .containsExactly("before", "cancelled", "next");
  }

  @Test
  void cancellationDuringActivityCommitWaitsForDurabilityAndRestoresInterrupt() throws Exception {
    var activity = new ManagedActivityLog(registry);
    activity.append(null, entry("before", "session", "run"));
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    var caller =
        Thread.ofVirtual().start(
            () -> {
              try {
                activity.appendWith(
                    null, entry("during", "session", "run"), root,
                    db -> {
                      entered.countDown();
                      try {
                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                          throw new AssertionError("Commit barrier timed out");
                      } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    });
                interrupted.set(Thread.currentThread().isInterrupted());
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    try {
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      caller.interrupt();
    } finally {
      release.countDown();
      caller.join(java.time.Duration.ofSeconds(10));
    }
    assertThat(caller.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
    assertThat(interrupted.get()).isTrue();
    activity.append(null, entry("next", "session", "next-run"));
    assertThat(activity.read(null))
        .extracting(ProfileEventLogEntry::id)
        .containsExactly("before", "during", "next");
  }

  /** 新規区間一覧について、旧形式ログを保持することを確認する。 */
  @Test
  void newSegmentsPreserveLegacyLogAndOnlyClosedOwnedBodiesCanBePlanned() throws Exception {
    Path legacy = root.resolve("logs/activity.jsonl");
    Files.createDirectories(legacy.getParent());
    Files.writeString(
        legacy, StorageDatabase.JSON.writeValueAsString(entry("legacy", null, null)) + "\n");
    String hash = StorageBackup.hash(legacy);
    var log = new ManagedActivityLog(registry, 1024);
    log.append(null, entry("new", null, null));
    var planner = new RetentionPlanner(registry);
    planner.updatePolicy("activity-raw", null, null, 1L);
    assertThat(planner.plan("activity-raw", null, 100, 100000).candidates()).isEmpty();
    log.closeSegment(null);
    assertThat(log.read(null))
        .extracting(ProfileEventLogEntry::id)
        .containsExactly("legacy", "new");
    assertThat(StorageBackup.hash(legacy)).isEqualTo(hash);
    log.append(null, entry("second", null, null));
    log.closeSegment(null);
    assertThat(planner.plan("activity-raw", null, 100, 100000).candidates()).hasSize(1);
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void anySessionOrRunArchiveStaysProtectedAndProjectScopesDoNotMix() throws Exception {
    String project = UUID.randomUUID().toString();
    var log = new ManagedActivityLog(registry, 1024);
    log.append(project, entry("project", "session", "run"));
    log.closeSegment(project);
    log.append(null, entry("global", null, null));
    log.closeSegment(null);
    assertThat(log.read(project)).extracting(ProfileEventLogEntry::id).containsExactly("project");
    assertThat(log.read(null)).extracting(ProfileEventLogEntry::id).containsExactly("global");
    var planner = new RetentionPlanner(registry);
    planner.updatePolicy("activity-raw", Duration.ofSeconds(1), null, null);
    assertThat(planner.status(project).protectionReasons()).containsKey("referenced");
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void changedOpenSegmentIsRetainedAndCannotBeAdoptedAsVerified() throws Exception {
    var log = new ManagedActivityLog(registry, 1024);
    log.append(null, entry("one", null, null));
    Path file;
    try (var files = Files.list(root.resolve("logs/activity-archives"))) {
      file = files.findFirst().orElseThrow();
    }
    Files.writeString(file, "externally changed");
    assertThatThrownBy(() -> log.closeSegment(null)).isInstanceOf(IllegalStateException.class);
    assertThat(Files.readString(file)).isEqualTo("externally changed");
    assertThat(new RetentionPlanner(registry).status(null).objects()).isEmpty();
  }

  /** データ保存の共通処理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sameSizeExternalChangeIsDetectedBeforeClosingOrCollection() throws Exception {
    var log = new ManagedActivityLog(registry, 1024);
    log.append(null, entry("one", null, null));
    Path file;
    try (var files = Files.list(root.resolve("logs/activity-archives"))) {
      file = files.findFirst().orElseThrow();
    }
    String changed = Files.readString(file).replace("one", "two");
    Files.writeString(file, changed);
    log.append(null, entry("next", null, null));
    assertThatThrownBy(() -> log.closeSegment(null)).isInstanceOf(IllegalStateException.class);
    assertThat(Files.readString(file)).startsWith(changed);
    assertThat(new RetentionPlanner(registry).status(null).objects()).isEmpty();
  }

  /** データ保存の共通処理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedMetadataCommitDoesNotPublishAnUncommittedArchive() throws Exception {
    registry.database.transaction(
        db -> {
          try (var query = db.createStatement()) {
            query.execute(
                "CREATE TRIGGER fail_activity BEFORE UPDATE ON activity_segments BEGIN SELECT RAISE(ABORT,'fixture disk/write failure'); END");
          }
          return null;
        });
    var log = new ManagedActivityLog(registry, 1024);
    assertThatThrownBy(() -> log.append(null, entry("uncommitted", null, null)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> log.read(null)).isInstanceOf(IllegalStateException.class);
    assertThat(new RetentionPlanner(registry).status(null).objects()).isEmpty();
  }

  /** データ保存の共通処理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void springWiresProductionProfileStoreAndExecutionCommandBeforeServing() throws Exception {
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "test", Map.of("rei.data-dir", root.toString())));
      context.register(Config.class);
      context.refresh();
      var store = context.getBean(dev.mikoto2000.rei.event.ProfileEventLogStore.class);
      assertThat(store.file()).isEqualTo(root.resolve("logs/activity.jsonl"));
      store.append(
          new dev.mikoto2000.rei.event.AgentEvent(
              "production",
              1,
              registry.clock.instant(),
              dev.mikoto2000.rei.event.AgentEventType.AGENT_RUN_COMPLETED,
              1,
              "session",
              null,
              "run",
              null,
              null,
              new dev.mikoto2000.rei.event.AgentRunCompletedPayload("run", 1),
              null));
      assertThat(store.readAll())
          .extracting(ProfileEventLogEntry::id)
          .containsExactly("production");
      assertThat(root.resolve("logs/activity.jsonl")).doesNotExist();
      assertThat(store.summarize().total()).isEqualTo(1);
      var command =
          new picocli.CommandLine(
              context.getBean(dev.mikoto2000.rei.core.command.StorageCommand.class));
      command.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
      assertThat(command.execute("maintenance", "status")).isZero();
    }
  }

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @org.springframework.context.annotation.Configuration
  @org.springframework.context.annotation.Import(StorageMigrationConfiguration.class)
  static class Config {
    /** {@code profile} の条件を再現するための補助情報を返す。 */
    @org.springframework.context.annotation.Bean
    dev.mikoto2000.rei.event.ProfileEventLogStore profile() {
      return new dev.mikoto2000.rei.event.ProfileEventLogStore();
    }

    /** {@code command} の条件を再現するための補助情報を返す。 */
    @org.springframework.context.annotation.Bean
    dev.mikoto2000.rei.core.command.StorageCommand command(RetentionPlanner planner) {
      return new dev.mikoto2000.rei.core.command.StorageCommand(planner);
    }
  }
}
