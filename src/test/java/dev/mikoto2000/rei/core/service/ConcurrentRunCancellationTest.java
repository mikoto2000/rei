package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

/** 並行実行キャンセルの結果と状態の契約を検証するテスト。 */
class ConcurrentRunCancellationTest {
  /** 対話から使う共通操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void backgroundCommandDoesNotConsumeAgentOrLegacyCancellation() {
    var service = new CommandCancellationService();
    service.begin(mock(Thread.class));
    service.cancel();
    var execution =
        new dev.mikoto2000.rei.core.execution.ActiveExecution(
            "image",
            UUID.randomUUID().toString(),
            "chat:main",
            Path.of("a"),
            dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE,
            "picture",
            java.time.Instant.now());
    try (var ignored = dev.mikoto2000.rei.core.execution.ExecutionScope.open(execution)) {
      assertThat(service.isCancellationRequested()).isFalse();
      assertThat(service.consumeCancellationRequested()).isFalse();
    }
    assertThat(service.consumeCancellationRequested()).isTrue();
    service.clear();
  }

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void cleanupDisposesOnlyItsOwnLiveSubscription() {
    var service = new CommandCancellationService();
    var run = new AgentRunContext("run", "chat:main", Path.of("a"));
    var stream = mock(Disposable.class);
    try (var ignored = AgentRunScope.open(run)) {
      service.begin(mock(Thread.class));
      service.register(stream);
      service.clear();
    }
    verify(stream).dispose();
  }

  /** 対話から使う共通操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellingAndClearingBDoesNotTouchA() {
    var service = new CommandCancellationService();
    var a = new AgentRunContext("a", "chat:main", Path.of("a"), UUID.randomUUID().toString());
    var b = new AgentRunContext("b", "chat:main", Path.of("b"), UUID.randomUUID().toString());
    var threadA = mock(Thread.class);
    var threadB = mock(Thread.class);
    var streamA = mock(Disposable.class);
    var streamB = mock(Disposable.class);
    try (var ignored = AgentRunScope.open(a)) {
      service.begin(threadA);
      service.register(streamA);
    }
    try (var ignored = AgentRunScope.open(b)) {
      service.begin(threadB);
      service.register(streamB);
      service.cancel();
      service.clear();
    }
    verify(streamB).dispose();
    verify(threadB).interrupt();
    verify(streamA, never()).dispose();
    verify(threadA, never()).interrupt();
    try (var ignored = AgentRunScope.open(a)) {
      assertThat(service.isCancellationRequested()).isFalse();
      service.cancel();
      assertThat(service.consumeCancellationRequested()).isTrue();
      service.clear();
    }
    verify(streamA).dispose();
    verify(threadA).interrupt();
  }
}
