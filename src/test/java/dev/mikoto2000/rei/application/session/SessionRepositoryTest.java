package dev.mikoto2000.rei.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.conversation.FileSessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会話保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SessionRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-09-16T08:00:00Z");

  /** 検証で使用する付随情報を、指定された条件に合わせて用意する。 */
  SessionMetadata metadata(String id) {
    return new SessionMetadata(id, "project", "first", now, now);
  }

  /** 受け入れ条件永続化された変更前実行先への引き渡しおよびについて、再開の後も対象の情報が残ることを確認する。 */
  @Test
  void acceptanceIsDurableBeforeDispatchAndSurvivesRestart() {
    var file = temp.resolve("sessions.json");
    var store = new FileSessionRepository(file);
    var first = metadata("one");
    store.accept(
        first, () -> assertThat(new FileSessionRepository(file).findById("one")).contains(first));
    assertThat(new FileSessionRepository(file).findById("one")).contains(first);
    assertThat(store.findById("missing")).isEmpty();
  }

  /** 継続入力について、識別情報を保持することを確認する。 */
  @Test
  void continuationPreservesIdentityAndTimeNeverMovesBackwards() {
    var store = new FileSessionRepository(temp.resolve("sessions.json"));
    var first = metadata("one");
    store.accept(first, () -> {});
    store.accept(first.touched(now.plusSeconds(10)), () -> {});
    store.accept(first.touched(now.plusSeconds(1)), () -> {});
    assertThat(store.findById("one")).contains(first.touched(now.plusSeconds(10)));
    assertThatThrownBy(
            () -> store.accept(new SessionMetadata("one", "other", "first", now, now), () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 前回永続化された状態を復元することを確認する。 */
  @Test
  void rejectionRestoresPreviousDurableState() {
    var file = temp.resolve("sessions.json");
    var store = new FileSessionRepository(file);
    assertThatThrownBy(
            () ->
                store.accept(
                    metadata("one"),
                    () -> {
                      throw new IllegalStateException("rejected");
                    }))
        .hasMessage("rejected");
    assertThat(new FileSessionRepository(file).findById("one")).isEmpty();
    store.accept(metadata("one"), () -> {});
    assertThatThrownBy(
            () ->
                store.accept(
                    metadata("one").touched(now.plusSeconds(10)),
                    () -> {
                      throw new IllegalStateException("rejected");
                    }))
        .hasMessage("rejected");
    assertThat(new FileSessionRepository(file).findById("one")).contains(metadata("one"));
  }

  /** 会話の継続管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void failedPersistenceNeverDispatches() throws Exception {
    var file = Files.writeString(temp.resolve("not-directory"), "file").resolve("sessions.json");
    var store = new FileSessionRepository(file);
    assertThatThrownBy(
            () ->
                store.accept(
                    metadata("one"),
                    () -> {
                      throw new AssertionError("must not enqueue");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.findById("one")).isEmpty();
  }

  /** 会話の継続管理で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentAdmissionNeverLosesRowsOrRegressesUpdatedAt() throws Exception {
    var file = temp.resolve("sessions.json");
    var store = new FileSessionRepository(file);
    var first = metadata("one");
    store.accept(first, () -> {});
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 1; i <= 30; i++) {
        int index = i;
        jobs.add(
            executor.submit(
                () -> {
                  store.accept(first.touched(now.plusSeconds(index)), () -> {});
                  store.accept(metadata("new-" + index), () -> {});
                  assertThat(store.findPage(null, null, 100)).isNotEmpty();
                }));
      }
      for (var job : jobs) job.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    var restored = new FileSessionRepository(file);
    assertThat(restored.findById("one")).contains(first.touched(now.plusSeconds(30)));
    assertThat(restored.findPage(null, null, 100)).hasSize(31);
  }
}
