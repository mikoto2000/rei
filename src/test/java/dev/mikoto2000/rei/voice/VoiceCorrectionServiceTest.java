package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 音声補正サービスの結果と状態の契約を検証するテスト。 */
class VoiceCorrectionServiceTest {
  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void pendingUtteranceCanBeCancelledIndividuallyBeforeLlmReturns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var input = input("交配の話");
    try (var service =
        new VoiceCorrectionService(
            properties(),
            this::snapshot,
            (raw, context) -> {
              entered.countDown();
              release.await();
              return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
            },
            new VoiceEventPublisher())) {
      var result = service.correct(input, () -> true).toCompletableFuture();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(service.pending()).containsExactly(input);
      assertThat(service.cancel(input.inputId())).isTrue();
      assertThat(result.join().reason()).isEqualTo("cancelled");
      assertThat(service.pending()).isEmpty();
      assertThat(service.cancel(input.inputId())).isFalse();
      release.countDown();
    } finally {
      release.countDown();
    }
  }

  /** 検証に使う対象。 */
  final ConversationTarget target =
      new ConversationTarget(
          new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");

  /** {@code input} の条件を再現するための補助情報を返す。 */
  ConversationInput input(String text) {
    return new ConversationInput(UUID.randomUUID(), InputSource.VOICE, target, text, Instant.now());
  }

  /** {@code properties} の条件を再現するための補助情報を返す。 */
  VoiceCorrectionProperties properties() {
    var p = new VoiceCorrectionProperties();
    p.setEnabled(true);
    p.setMaxTotalTokens(0);
    return p;
  }

  /** 検証で使用する状態の記録を、指定された条件に合わせて用意する。 */
  VoiceCorrectionContext.Snapshot snapshot(ConversationInput i) {
    return new VoiceCorrectionContext.Snapshot(
        i.target().project().id(), "test", List.of("微分の話"), List.of());
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void adoptsValidCandidateAndBypassesWhenOffOrRawControl() throws Exception {
    var p = properties();
    var calls = new AtomicInteger();
    try (var service =
        new VoiceCorrectionService(
            p,
            this::snapshot,
            (raw, context) -> {
              calls.incrementAndGet();
              return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
            },
            new VoiceEventPublisher())) {
      assertThat(
              service
                  .correct(input("交配の話"), () -> true)
                  .toCompletableFuture()
                  .get(2, TimeUnit.SECONDS)
                  .text())
          .isEqualTo("勾配の話");
      for (String raw : List.of("実行を停止。", "はい", "いいえ"))
        assertThat(service.correct(input(raw), () -> true).toCompletableFuture().join().text())
            .isEqualTo(raw);
      p.setEnabled(false);
      assertThat(service.correct(input("交配の話"), () -> true).toCompletableFuture().join().reason())
          .isEqualTo("bypassed");
      assertThat(calls).hasValue(1);
    }
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void deadlineWinsOnceEvenIfTransportIgnoresInterruption() throws Exception {
    var timer = mock(ScheduledExecutorService.class);
    var actions = new CopyOnWriteArrayList<Runnable>();
    when(timer.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(
            invocation -> {
              actions.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var events = new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    try (var service =
        new VoiceCorrectionService(
            properties(),
            this::snapshot,
            (raw, context) -> {
              entered.countDown();
              boolean done = false;
              while (!done)
                try {
                  release.await();
                  done = true;
                } catch (InterruptedException ignored) {
                }
              return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
            },
            publisher,
            timer,
            () -> 0)) {
      var result = service.correct(input("交配の話"), () -> true).toCompletableFuture();
      var completions = new AtomicInteger();
      result.thenRun(completions::incrementAndGet);
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      actions.getFirst().run();
      assertThat(result.join().reason()).isEqualTo("timeout");
      release.countDown();
      assertThat(completions).hasValue(1);
      assertThat(
              events.stream().filter(e -> e.type() == VoiceEventPublisher.Type.CORRECTION_RESULT))
          .hasSize(1);
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void queueIsBoundedAndSnapshotsAreTakenBeforeQueueing() throws Exception {
    var p = properties();
    p.setMaxQueuedRequests(1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var captures = new AtomicInteger();
    var contexts = new CopyOnWriteArrayList<VoiceCorrectionContext.Snapshot>();
    try (var service =
        new VoiceCorrectionService(
            p,
            i ->
                new VoiceCorrectionContext.Snapshot(
                    target.project().id(),
                    "snapshot-" + captures.incrementAndGet(),
                    List.of(),
                    List.of()),
            (raw, context) -> {
              contexts.add(context);
              entered.countDown();
              release.await();
              return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
            },
            new VoiceEventPublisher())) {
      var first = service.correct(input("交配の話"), () -> true).toCompletableFuture();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      var second = service.correct(input("交配の話"), () -> true).toCompletableFuture();
      assertThat(service.correct(input("交配の話"), () -> true).toCompletableFuture().join().reason())
          .isEqualTo("queue_full");
      release.countDown();
      assertThat(first.get(2, TimeUnit.SECONDS).text()).isEqualTo("勾配の話");
      assertThat(second.get(2, TimeUnit.SECONDS).text()).isEqualTo("勾配の話");
      assertThat(contexts)
          .extracting(VoiceCorrectionContext.Snapshot::projectName)
          .containsExactly("snapshot-1", "snapshot-2");
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAndSelectionChangeInvalidateLateCandidate() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var current = new AtomicBoolean(true);
    try (var service =
        new VoiceCorrectionService(
            properties(),
            this::snapshot,
            (raw, context) -> {
              entered.countDown();
              release.await();
              return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
            },
            new VoiceEventPublisher())) {
      var result = service.correct(input("交配の話"), current::get).toCompletableFuture();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      current.set(false);
      release.countDown();
      assertThat(result.get(2, TimeUnit.SECONDS).reason()).isEqualTo("cancelled");
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void failuresAndSecretsNeverEnterDiagnosticsAndCriticalRawRequiresReview() throws Exception {
    var events = new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    var calls = new AtomicInteger();
    try (var service =
        new VoiceCorrectionService(
            properties(),
            this::snapshot,
            (raw, context) -> {
              calls.incrementAndGet();
              throw new IllegalStateException("password=topsecret " + raw);
            },
            publisher)) {
      var result =
          service
              .correct(input("対象を削除"), () -> true)
              .toCompletableFuture()
              .get(2, TimeUnit.SECONDS);
      assertThat(result.text()).isEqualTo("対象を削除");
      assertThat(result.confirmationRequired()).isTrue();
      assertThat(
              service
                  .correct(input("password=topsecret"), () -> true)
                  .toCompletableFuture()
                  .join()
                  .reason())
          .isEqualTo("sensitive_input");
      assertThat(calls).hasValue(1);
      assertThat(events.toString()).doesNotContain("topsecret", "対象を削除", "password");
    }
  }
}
