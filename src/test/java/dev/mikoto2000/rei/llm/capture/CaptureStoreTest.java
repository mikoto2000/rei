package dev.mikoto2000.rei.llm.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** 取得記録保存先の結果と状態の契約を検証するテスト。 */
class CaptureStoreTest {
  /** 検証に使う保存先。 */
  CaptureStore store = new CaptureStore(Clock.systemUTC());

  /** モデル応答の記録の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void initiallyOffAndReservationIsScopedAndCancellable() {
    assertThat(store.sessions()).isEmpty();
    store.reserve("client-a", "conversation-a");
    assertThat(store.accept("client-b", "conversation-a", "other", "run-b")).isFalse();
    assertThat(store.accept("client-a", "conversation-b", "other", "run-c")).isFalse();
    assertThat(store.off("client-a", "conversation-a")).isTrue();
    assertThat(store.accept("client-a", "conversation-a", "submission-a", "run-a")).isFalse();
  }

  /** モデル応答の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void submissionConsumesReservationOnceAndOnlyExactRootIsCaptured() {
    store.reserve("client-a", "conversation-a");
    assertThat(store.accept("client-a", "conversation-a", "submission-a", "run-a")).isTrue();
    assertThat(store.accept("client-a", "conversation-a", "submission-b", "run-b")).isFalse();
    assertThat(store.logicalCall("run-b", "run-a")).isNull();
    assertThat(store.logicalCall("run-a", null)).isNotNull();
    assertThat(store.sessions().getFirst().submissionId()).isEqualTo("submission-a");
  }

  /** モデル応答の記録で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void onlyOneConcurrentSubmissionWins() throws Exception {
    store.reserve("client", "conversation");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                gate.await();
                return store.accept("client", "conversation", "a", "a");
              });
      var b =
          executor.submit(
              () -> {
                gate.await();
                return store.accept("client", "conversation", "b", "b");
              });
      gate.countDown();
      assertThat(a.get() ^ b.get()).isTrue();
    }
  }

  /** モデル応答の記録で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void limitsRejectWholeBodiesAndMarkAttemptOverflow() {
    activate();
    var call = store.logicalCall("run", null);
    assertThat(
            store
                .prepare(call, new byte[CaptureStore.BODY_LIMIT + 1], "application/json", null)
                .captureState())
        .isEqualTo("SKIPPED_TOO_LARGE");
    for (int i = 0; i < 16; i++)
      assertThat(
              store
                  .prepare(call, new byte[CaptureStore.BODY_LIMIT], "application/json", null)
                  .captureState())
          .isEqualTo("COMPLETE");
    assertThat(store.prepare(call, new byte[1], "application/json", null).captureState())
        .isEqualTo("SKIPPED_TOO_LARGE");
    for (int i = 18; i < 32; i++) store.prepare(call, null, null, "SKIPPED_UNSUPPORTED");
    assertThat(store.prepare(call, null, null, "SKIPPED_UNSUPPORTED")).isNull();
    assertThat(store.sessions().getFirst().incomplete()).isTrue();
    assertThat(store.sessions().getFirst().bytes()).isEqualTo(CaptureStore.RUN_LIMIT);
  }

  /** 保持内容の消去を削除することを確認する。 */
  @Test
  void deleteClearAndStartFailureRemoveActiveOwnership() {
    activate();
    assertThat(store.delete("run")).isTrue();
    assertThat(store.logicalCall("run", null)).isNull();
    activate();
    store.startFailed("run");
    assertThat(store.logicalCall("run", null)).isNull();
    store.clear();
    assertThat(store.sessions()).isEmpty();
  }

  /** モデル応答の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ttlStartsAtRunEndAndMaximumThreeSessions() {
    var clock = new MutableClock();
    store = new CaptureStore(clock);
    activate();
    clock.now = clock.now.plus(Duration.ofHours(1));
    assertThat(store.sessions()).hasSize(1);
    store.finish("run");
    clock.now = clock.now.plus(Duration.ofMinutes(30));
    assertThat(store.sessions()).isEmpty();
    for (int i = 0; i < 4; i++) {
      store.reserve("client", "conversation");
      store.accept("client", "conversation", "s" + i, "r" + i);
      store.finish("r" + i);
    }
    assertThat(store.sessions()).hasSize(3);
  }

  /** モデル応答の記録で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void originalsAreDefensiveAndIdsCannotCrossSessions() {
    activate();
    var bytes = new byte[] {1, 2, 3};
    var attempt = store.prepare(store.logicalCall("run", null), bytes, "application/json", null);
    bytes[0] = 8;
    var first = store.body(attempt.attemptId());
    first[0] = 9;
    assertThat(store.body(attempt.attemptId())).containsExactly(1, 2, 3);
    assertThatThrownBy(() -> store.body("missing")).isInstanceOf(IllegalArgumentException.class);
    store.delete("run");
    assertThatThrownBy(() -> store.body(attempt.attemptId()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** {@code activate} の条件を再現するため、検証用の操作や状態を扱う。 */
  void activate() {
    store.reserve("client", "conversation");
    store.accept("client", "conversation", "submission", "run");
  }

  /** 検証条件を再現するための変更可能な時刻の取得元。 */
  static class MutableClock extends Clock {
    /** 日時や期限の条件を再現するために使う検証時刻。 */
    Instant now = Instant.parse("2026-10-09T00:00:00Z");

    /** 検証用に、モデル応答の記録で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId zone) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return now;
    }
  }
}
