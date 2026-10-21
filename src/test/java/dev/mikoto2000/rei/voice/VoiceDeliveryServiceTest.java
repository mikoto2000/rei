package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 音声配信サービスの結果と状態の契約を検証するテスト。 */
class VoiceDeliveryServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使うシェル。 */
  ShellConversationService shell;

  /** 検証に使う配信。 */
  VoiceDeliveryService delivery;

  /** 検証に使うタスク一覧。 */
  final List<Runnable> tasks = new ArrayList<>();

  /** 検証に使うプロンプト一覧。 */
  final List<String> prompts = new ArrayList<>();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    projects = new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")));
    var router =
        new ConversationInputRouter(tasks::add, (owner, prompt, mailbox) -> prompts.add(prompt));
    shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")), clock),
            router::submit,
            false,
            router,
            clock);
    delivery = new VoiceDeliveryService(shell, clock, new VoiceEventPublisher());
  }

  /** {@code input} の条件を再現するための補助情報を返す。 */
  ConversationInput input(ConversationTarget target) {
    return new ConversationInput(
        UUID.randomUUID(), InputSource.VOICE, target, "recognized", clock.instant());
  }

  /** 音声による対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void correctionReviewIsNotToolApprovalAndCannotDispatchWithoutExplicitConfirmation() {
    var client = projects.newClient();
    ConversationTarget target;
    try (var scope = client.open()) {
      target = shell.captureTarget();
      delivery.bind(client, target);
    }
    var raw = input(target);
    delivery.requireReview(raw);
    assertThat(tasks).isEmpty();
    assertThat(delivery.pending()).containsExactly(raw);
    delivery.confirm(raw.inputId(), null);
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly(raw.text());
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void switchingAwayAndBackInvalidatesCapturedBinding() {
    var client = projects.newClient();
    ConversationTarget target;
    try (var scope = client.open()) {
      target = shell.captureTarget();
      delivery.bind(client, target);
      shell.newConversation();
      shell.resume(target.sessionId());
    }
    assertThat(delivery.targetIsCurrent(target)).isFalse();
    assertThatThrownBy(() -> delivery.accept(input(target)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(tasks).isEmpty();
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void stopIdentityCannotBecomeAnAgentInputAfterDisablingInterrupt() {
    var options =
        new java.util.concurrent.atomic.AtomicReference<>(
            new VoiceAdvancedOptions(
                false, "れい", true, false, VoiceAdvancedOptions.defaults().ttsVoice(), 800));
    delivery = new VoiceDeliveryService(shell, clock, new VoiceEventPublisher(), options::get);
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
      delivery.bind(shell.captureClient(), target);
    }
    var stop =
        new ConversationInput(
            UUID.randomUUID(), InputSource.VOICE, target, "実行を停止", clock.instant());
    delivery.accept(stop);
    options.set(VoiceAdvancedOptions.defaults());
    delivery.accept(stop);
    assertThat(tasks).isEmpty();
    assertThatThrownBy(
            () ->
                delivery.accept(
                    new ConversationInput(
                        stop.inputId(), InputSource.VOICE, target, "different", clock.instant())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void ordinaryInputIdentityCannotBeReusedForAStopPayload() {
    var options =
        new VoiceAdvancedOptions(
            false, "れい", true, false, VoiceAdvancedOptions.defaults().ttsVoice(), 800);
    delivery = new VoiceDeliveryService(shell, clock, new VoiceEventPublisher(), () -> options);
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
      delivery.bind(shell.captureClient(), target);
    }
    var ordinary = input(target);
    delivery.accept(ordinary);
    assertThatThrownBy(
            () ->
                delivery.accept(
                    new ConversationInput(
                        ordinary.inputId(), InputSource.VOICE, target, "実行を停止", clock.instant())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void explicitStopCancelsOnlyCapturedClientsActiveRunAndIsIdempotent() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var cancelled = new ArrayList<String>();
    var router =
        new ConversationInputRouter(
            tasks::add,
            (owner, prompt, mailbox) -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(new FileSessionRepository(root.resolve("stop.json")), clock),
            router::submit,
            false,
            router,
            clock);
    shell.onActiveCancellation(
        context -> {
          cancelled.add(context.runId());
          return true;
        });
    var options =
        new VoiceAdvancedOptions(
            false, "れい", true, false, VoiceAdvancedOptions.defaults().ttsVoice(), 800);
    delivery = new VoiceDeliveryService(shell, clock, new VoiceEventPublisher(), () -> options);
    var first = projects.newClient();
    ConversationTarget target;
    AgentRunContext run;
    try (var scope = first.open()) {
      target = shell.captureTarget();
      delivery.bind(first, target);
      run = shell.submit("work");
    }
    var thread = new Thread(tasks.removeFirst());
    thread.start();
    try {
      assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var other = projects.newClient();
      try (var scope = other.open()) {
        shell.resume(target.sessionId());
        delivery.bind(other, target);
      }
      delivery.accept(
          new ConversationInput(
              UUID.randomUUID(), InputSource.VOICE, target, "実行を停止", clock.instant()));
      assertThat(cancelled).isEmpty();
      try (var scope = first.open()) {
        delivery.bind(first, target);
      }
      var stop =
          new ConversationInput(
              UUID.randomUUID(), InputSource.VOICE, target, "実行を停止。", clock.instant());
      delivery.accept(stop);
      delivery.accept(stop);
      assertThat(cancelled).containsExactly(run.runId());
      assertThat(tasks).isEmpty();
    } finally {
      release.countDown();
      thread.join(3000);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void completedVoiceRunOwnershipCannotCrossShellClientsEvenInSameSession() {
    var owners = new ArrayList<AgentRunContext>();
    var router =
        new ConversationInputRouter(tasks::add, (owner, prompt, mailbox) -> owners.add(owner));
    shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(new FileSessionRepository(root.resolve("owners.json")), clock),
            router::submit,
            false,
            router,
            clock);
    delivery = new VoiceDeliveryService(shell, clock, new VoiceEventPublisher());
    var first = projects.newClient();
    ConversationTarget target;
    try (var scope = first.open()) {
      target = shell.captureTarget();
      delivery.bind(first, target);
    }
    delivery.accept(input(target));
    tasks.removeFirst().run();
    assertThat(delivery.ownsRun(owners.getFirst())).isTrue();
    var other = projects.newClient();
    try (var scope = other.open()) {
      shell.resume(target.sessionId());
      delivery.bind(other, target);
    }
    assertThat(delivery.ownsRun(owners.getFirst())).isFalse();
  }

  /** 取得済みクライアントについて、実行の担当を許可することを確認する。 */
  @Test
  void capturedClientAllowsWorkerWithoutThreadLocalButNotAfterSessionChange() {
    var client = projects.newClient();
    ConversationTarget target;
    try (var scope = client.open()) {
      target = shell.captureTarget();
      delivery.bind(client, target);
    }
    delivery.accept(input(target));
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly("recognized");
    try (var scope = client.open()) {
      shell.newConversation();
    }
    assertThat(delivery.targetIsCurrent(target)).isFalse();
    assertThatThrownBy(() -> delivery.accept(input(target)))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void anotherClientSelectionCannotRedirectOrStopCapturedVoiceClient() {
    var first = projects.newClient();
    ConversationTarget target;
    try (var scope = first.open()) {
      target = shell.captureTarget();
      delivery.bind(first, target);
    }
    try (var scope = projects.newClient().open()) {
      shell.newConversation();
    }
    assertThat(delivery.targetIsCurrent(target)).isTrue();
    delivery.accept(input(target));
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly("recognized");
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void confirmationPublishesOnlyIdentityAndCorrectionReachesCommonGateway() {
    var events = new ArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    delivery = new VoiceDeliveryService(shell, clock, publisher);
    delivery.setConfirmation(true);
    ConversationTarget target;
    try (var scope = projects.newClient().open()) {
      target = shell.captureTarget();
      delivery.bind(shell.captureClient(), target);
    }
    var input = input(target);
    delivery.accept(input);
    assertThat(tasks).isEmpty();
    assertThat(events)
        .anyMatch(
            e ->
                e.type() == VoiceEventPublisher.Type.REVIEW_REQUIRED
                    && e.detail().equals(input.inputId().toString()));
    assertThat(events).noneMatch(e -> e.detail().contains("recognized"));
    delivery.confirm(input.inputId(), "corrected");
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly("corrected");
    assertThat(delivery.pending()).isEmpty();
  }
}
