package dev.mikoto2000.rei.application.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 会話入力受付窓口の結果と状態の契約を検証するテスト。 */
class ConversationInputGatewayTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使う受付から終了までの管理。 */
  SessionLifecycle lifecycle;

  /** 検証に使うシェル。 */
  ShellConversationService shell;

  /** 検証に使う入力の振り分け。 */
  ConversationInputRouter router;

  /** 検証に使うタスク一覧。 */
  final List<Runnable> tasks = new ArrayList<>();

  /** 検証に使うexecuted。 */
  final List<String> executed = new ArrayList<>();

  /** 検証に使う所有者一覧。 */
  final List<AgentRunContext> owners = new ArrayList<>();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    lifecycle =
        new SessionLifecycle(new FileSessionRepository(temp.resolve("sessions.json")), clock);
    router =
        new ConversationInputRouter(
            tasks::add,
            (owner, prompt, queue) -> {
              owners.add(owner);
              executed.add(prompt);
            });
    shell = new ShellConversationService(projects, lifecycle, router::submit, false, router, clock);
  }

  /** {@code voice} の条件を再現するための補助情報を返す。 */
  ConversationInput voice(ConversationTarget target, String text) {
    return new ConversationInput(
        UUID.randomUUID(), InputSource.VOICE, target, text, clock.instant());
  }

  /** 会話入力の受付の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void keyboardAndMockVoiceUseSameSessionRunnerAndShellAuthority() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      shell.submit("keyboard");
      target = shell.captureTarget();
    }
    var input = voice(target, "voice");
    var accepted = shell.submit(input);
    assertThat(shell.submit(input)).isEqualTo(accepted);
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run();
    tasks.removeFirst().run();
    assertThat(executed).containsExactly("keyboard", "voice");
    assertThat(owners).extracting(AgentRunContext::conversationId).containsOnly(target.sessionId());
    assertThat(owners)
        .extracting(AgentRunContext::requestSource)
        .containsOnly(AgentRunContext.RequestSource.SHELL);
    assertThat(owners)
        .extracting(AgentRunContext::mode)
        .containsOnly(AgentRunContext.Mode.EXCLUSIVE);
    assertThat(owners).extracting(AgentRunContext::voiceInput).containsExactly(false, true);
  }

  /** 会話入力の受付で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void capturedSessionDoesNotFollowLaterSelectionOrWorkerThreadLocal() {
    ConversationTarget old;
    try (var scope = projects.newClient().open()) {
      old = shell.captureTarget();
      shell.newConversation();
      assertThat(shell.currentSessionId()).isNotEqualTo(old.sessionId());
    }
    var accepted = shell.submit(voice(old, "old session"));
    assertThat(accepted.conversationId()).isEqualTo(old.sessionId());
    tasks.removeFirst().run();
  }

  /** 会話入力の受付で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void voiceCannotExecuteSlashCommandsEvenWithLeadingWhitespace() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    assertThatThrownBy(() -> shell.submit(voice(target, "  /voice off")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tasks).isEmpty();
  }

  /** 会話入力の受付の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void voicePendingIsBoundedAndCancellationReleasesCapacity() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      shell.submit("blocking keyboard");
      target = shell.captureTarget();
    }
    var one = voice(target, "one");
    var two = voice(target, "two");
    var three = voice(target, "three");
    shell.submit(one);
    shell.submit(two);
    shell.submit(three);
    assertThat(shell.pending(target))
        .extracting(ConversationInput::inputId)
        .containsExactly(one.inputId(), two.inputId(), three.inputId());
    var four = voice(target, "four");
    assertThatThrownBy(() -> shell.submit(four))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    assertThat(shell.cancelPending(target, two.inputId())).isTrue();
    assertThat(shell.cancelPending(target, two.inputId())).isFalse();
    shell.submit(four);
    while (!tasks.isEmpty()) tasks.removeFirst().run();
    assertThat(executed).containsExactly("blocking keyboard", "one", "three", "four");
    assertThat(shell.pending(target)).isEmpty();
    assertThat(shell.submit(two).runId()).isNotBlank(); // cancelled ID remains deduplicated
    assertThat(tasks).isEmpty();
  }

  /** 会話入力の受付の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationCannotCrossSessionBoundary() {
    ConversationTarget first, second;
    try (var scope = projects.newClient().open()) {
      shell.submit("block");
      first = shell.captureTarget();
      shell.newConversation();
      second = shell.captureTarget();
    }
    var input = voice(first, "pending");
    shell.submit(input);
    assertThat(shell.cancelPending(second, input.inputId())).isFalse();
    assertThat(shell.pending(first)).containsExactly(input);
  }

  /** 会話入力の受付で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void reusedIdWithChangedPayloadIsRejected() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var input = voice(target, "first");
    shell.submit(input);
    assertThatThrownBy(
            () ->
                shell.submit(
                    new ConversationInput(
                        input.inputId(), InputSource.VOICE, target, "changed", input.createdAt())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 会話入力の受付で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void admissionFailureCanBeRetriedWithoutPoisoningId() {
    var tries = new java.util.concurrent.atomic.AtomicInteger();
    var gateway =
        new ConversationInputGateway(
            lifecycle,
            (owner, prompt) -> {
              if (tries.getAndIncrement() == 0)
                throw new java.util.concurrent.RejectedExecutionException();
              router.submit(owner, prompt);
            },
            router,
            clock);
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var input = voice(target, "retry");
    assertThatThrownBy(() -> gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    assertThat(gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE)).isNotNull();
    assertThat(tasks).hasSize(1);
  }

  /** 会話入力の受付で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void foreignProjectSessionIsRejectedBeforeDispatch() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var foreign =
        new ConversationTarget(
            new ProjectContext(UUID.randomUUID().toString(), "foreign", temp), target.sessionId());
    assertThatThrownBy(() -> shell.submit(voice(foreign, "wrong owner")))
        .isInstanceOf(dev.mikoto2000.rei.application.run.SessionConflictException.class);
    assertThat(tasks).isEmpty();
  }

  /** 会話入力の受付で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void ledgerIsBoundedAndNeverEvictsActiveInputsEvenAfterRetention() {
    var mutableClock = org.mockito.Mockito.mock(Clock.class);
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant());
    var gateway =
        new ConversationInputGateway(
            lifecycle, router::submit, router, mutableClock, 2, Duration.ofMinutes(30));
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var first = voice(target, "first");
    var second = voice(target, "second");
    var third = voice(target, "third");
    gateway.submit(first, AgentRunContext.Mode.EXCLUSIVE);
    gateway.submit(second, AgentRunContext.Mode.EXCLUSIVE);
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant().plusSeconds(1801));
    assertThat(gateway.submit(first, AgentRunContext.Mode.EXCLUSIVE).conversationId())
        .isEqualTo(target.sessionId());
    assertThatThrownBy(() -> gateway.submit(third, AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    while (!tasks.isEmpty()) tasks.removeFirst().run();
    gateway.submit(
        new ConversationInput(
            third.inputId(), third.source(), third.target(), third.text(), mutableClock.instant()),
        AgentRunContext.Mode.EXCLUSIVE);
    assertThat(tasks).hasSize(1);
  }

  /** 会話入力の受付で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void concurrentDuplicateEventsHaveOnlyOneAdmission() throws Exception {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var input = voice(target, "once");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<java.util.concurrent.Future<AgentRunContext>>();
      for (int i = 0; i < 8; i++) calls.add(executor.submit(() -> shell.submit(input)));
      var owner = calls.getFirst().get();
      for (var call : calls) assertThat(call.get()).isEqualTo(owner);
    }
    assertThat(tasks).hasSize(1);
  }

  /** 会話入力の受付の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void mockVoiceAndKeyboardKeepToolApprovalRequired() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      shell.submit("keyboard");
      target = shell.captureTarget();
    }
    var voiceOwner = shell.submit(voice(target, "voice"));
    tasks.removeFirst().run();
    tasks.removeFirst().run();
    var guard =
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
                new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                    true, Set.of(), Set.of(), Map.of())),
            new dev.mikoto2000.rei.event.AgentEventFactory(clock),
            event -> {});
    for (var owner : owners)
      assertThatThrownBy(() -> guard.check("writeMultiFile", "{}", owner))
          .isInstanceOf(dev.mikoto2000.rei.core.policy.ToolPermissionException.class);
    assertThat(voiceOwner.requestSource()).isEqualTo(owners.getFirst().requestSource());
  }

  /** 会話入力の受付で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void voiceIsRejectedWhenExistingRunQueueIsUnavailable() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var gateway = new ConversationInputGateway(lifecycle, (owner, prompt) -> {}, null, clock);
    assertThatThrownBy(() -> gateway.submit(voice(target, "voice"), AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 会話入力の受付の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void voiceCancellationKeepsExistingRunRegistryConsistent() {
    var registry = new dev.mikoto2000.rei.application.run.RunRegistry(clock);
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var factory = new dev.mikoto2000.rei.event.AgentEventFactory(clock);
    try (var runs =
        new dev.mikoto2000.rei.application.run.RunService(
            registry,
            bus,
            factory,
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            router::cancelQueued)) {
      var registered =
          new ShellConversationService(
              projects,
              lifecycle,
              (owner, prompt) -> {
                registry.register(owner);
                router.submit(owner, prompt, work -> runs.execute(owner, work));
              },
              false,
              router,
              clock);
      registered.onPendingCancellation(owner -> runs.cancelQueuedOnly(owner.runId()).accepted());
      ConversationTarget target;
      try (var scope = projects.newClient().open()) {
        registered.submit("block");
        target = registered.captureTarget();
      }
      var input = voice(target, "cancel");
      var owner = registered.submit(input);
      assertThat(registered.cancelPending(target, input.inputId())).isTrue();
      assertThat(registry.get(owner.runId()).status())
          .isEqualTo(dev.mikoto2000.rei.application.run.RunStatus.CANCELLED);
      while (!tasks.isEmpty()) tasks.removeFirst().run();
      assertThat(executed).containsExactly("block");
    }
  }

  /** 会話入力の受付で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void expiredCompletedInputCannotBeReplayedAfterLedgerPruning() {
    var mutableClock = org.mockito.Mockito.mock(Clock.class);
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant());
    var gateway =
        new ConversationInputGateway(
            lifecycle, router::submit, router, mutableClock, 1, Duration.ofMinutes(30));
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var input = voice(target, "once");
    gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE);
    tasks.removeFirst().run();
    org.mockito.Mockito.when(mutableClock.instant()).thenReturn(clock.instant().plusSeconds(1801));
    assertThatThrownBy(() -> gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(tasks).isEmpty();
  }

  /** 会話入力の受付の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void keyboardInputCanAlsoCancelThroughCommonGateway() {
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var input =
        new ConversationInput(
            UUID.randomUUID(), InputSource.KEYBOARD, target, "cancel keyboard", clock.instant());
    shell.submit(input);
    assertThat(shell.cancelPending(target, input.inputId())).isTrue();
    while (!tasks.isEmpty()) tasks.removeFirst().run();
    assertThat(executed).isEmpty();
  }

  /** 会話入力の受付で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void acceptanceDisplayPrecedesEvenAnInlineRunner() {
    var order = new ArrayList<String>();
    var immediateRouter =
        new ConversationInputRouter(Runnable::run, (owner, prompt, mailbox) -> order.add("answer"));
    var gateway =
        new ConversationInputGateway(lifecycle, immediateRouter::submit, immediateRouter, clock);
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var input = voice(target, "voice");
    gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE, () -> order.add("user"));
    gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE, () -> order.add("duplicate"));
    assertThat(order).containsExactly("user", "answer");
  }

  /** 会話入力の受付で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void acceptanceDisplayPrecedesAnAsyncRunnerThatStartsBeforeDispatchReturns() throws Exception {
    var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var startedRouter =
          new ConversationInputRouter(
              task -> {
                try {
                  executor.submit(task).get(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception error) {
                  throw new IllegalStateException(error);
                }
              },
              (owner, prompt, mailbox) -> order.add("answer"));
      var gateway =
          new ConversationInputGateway(lifecycle, startedRouter::submit, startedRouter, clock);
      ConversationTarget target;
      try (var scope = projects.newClient().open()) {
        target = shell.captureTarget();
      }
      gateway.submit(
          voice(target, "voice"), AgentRunContext.Mode.EXCLUSIVE, () -> order.add("user"));
      assertThat(order).containsExactly("user", "answer");
    }
  }

  /** 会話入力の受付で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  @Timeout(10)
  void runnerWaitsWhenTheSubmittingThreadIsStillDisplayingAcceptance() throws Exception {
    var jobs = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
    var rendering = new java.util.concurrent.CountDownLatch(1);
    var finishRendering = new java.util.concurrent.CountDownLatch(1);
    var workerStarted = new java.util.concurrent.CountDownLatch(1);
    var answered = new java.util.concurrent.CountDownLatch(1);
    var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var delayedRouter =
        new ConversationInputRouter(
            jobs::add,
            (owner, prompt, mailbox) -> {
              order.add("answer");
              answered.countDown();
            });
    var gateway =
        new ConversationInputGateway(lifecycle, delayedRouter::submit, delayedRouter, clock);
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
    }
    var input = voice(target, "voice");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var submitting =
          executor.submit(
              () ->
                  gateway.submit(
                      input,
                      AgentRunContext.Mode.EXCLUSIVE,
                      () -> {
                        rendering.countDown();
                        try {
                          assertThat(
                                  finishRendering.await(3, java.util.concurrent.TimeUnit.SECONDS))
                              .isTrue();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        order.add("user");
                      }));
      java.util.concurrent.Future<?> working;
      try {
        assertThat(rendering.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var job = jobs.poll(3, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(job).isNotNull();
        working =
            executor.submit(
                () -> {
                  workerStarted.countDown();
                  job.run();
                });
        assertThat(workerStarted.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(answered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
      } finally {
        finishRendering.countDown();
      }
      submitting.get(3, java.util.concurrent.TimeUnit.SECONDS);
      working.get(3, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(order).containsExactly("user", "answer");
    }
  }
}
