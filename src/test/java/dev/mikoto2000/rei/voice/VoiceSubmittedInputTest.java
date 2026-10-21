package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectClient;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.ui.shell.JLineShellEventOutput;
import dev.mikoto2000.rei.ui.shell.UserInputFrame;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 音声受付済み入力の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class VoiceSubmittedInputTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);

  /** 検証に使うタスク一覧。 */
  final List<Runnable> tasks = new ArrayList<>();

  /** 検証に使うプロンプト一覧。 */
  final List<String> prompts = new ArrayList<>();

  /** 検証に使う受付済み。 */
  final List<ConversationInput> submitted = new ArrayList<>();

  /** 検証に使う表示。 */
  final ByteArrayOutputStream display = new ByteArrayOutputStream();

  /** 検証に使う終端。 */
  Terminal terminal;

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使うシェル。 */
  ShellConversationService shell;

  /** 検証に使う配信。 */
  VoiceDeliveryService delivery;

  /** 検証に使う対象。 */
  ConversationTarget target;

  /** 検証に使うクライアント。 */
  ProjectClient client;

  /** 検証に使う拒否。 */
  boolean reject;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    projects = new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")));
    var router =
        new ConversationInputRouter(
            task -> {
              if (reject) throw new RejectedExecutionException("fixture");
              tasks.add(task);
            },
            (owner, prompt, mailbox) -> prompts.add(prompt));
    shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")), clock),
            router::submit,
            false,
            router,
            clock);
    delivery = new VoiceDeliveryService(shell, clock, new VoiceEventPublisher());
    client = projects.newClient();
    try (var scope = client.open()) {
      target = shell.captureTarget();
      delivery.bind(shell.captureClient(), target);
    }
    terminal =
        new DumbTerminal(
            "submitted-voice",
            Terminal.TYPE_DUMB,
            new ByteArrayInputStream(new byte[0]),
            display,
            StandardCharsets.UTF_8);
    var reader = LineReaderBuilder.builder().terminal(terminal).build();
    var output = new JLineShellEventOutput(reader);
    delivery.onSubmitted(
        input -> {
          submitted.add(input);
          // The UI uses the display/acceptance time, not the earlier speech timestamp.
          output.printBlock(UserInputFrame.format(input.text(), LocalTime.of(1, 37, 16)));
        });
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void closeTerminal() throws Exception {
    terminal.close();
  }

  /** {@code input} の条件を再現するための補助情報を返す。 */
  ConversationInput input(String text) {
    return new ConversationInput(
        UUID.randomUUID(), InputSource.VOICE, target, text, clock.instant().minusSeconds(60));
  }

  /** 音声による対話で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void automaticAcceptedVoicePrintsKeyboardFrameExactlyOnce() {
    var input = input("音声入力のテスト");
    delivery.accept(input);
    delivery.accept(input);
    assertThat(submitted).containsExactly(input);
    String ls = System.lineSeparator();
    assertThat(display.toString(StandardCharsets.UTF_8))
        .isEqualTo(ls + "┌ User (01:37:16)" + ls + "音声入力のテスト" + ls + "└" + ls + ls);
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly(input.text());
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void confirmationPrintsOnlyTheAcceptedCorrection() {
    delivery.setConfirmation(true);
    var input = input("未確認の認識文");
    delivery.accept(input);
    assertThat(display.toString(StandardCharsets.UTF_8)).isEmpty();
    delivery.confirm(input.inputId(), "訂正した文章\n2行目");
    assertThat(submitted)
        .singleElement()
        .satisfies(
            accepted -> {
              assertThat(accepted.inputId()).isEqualTo(input.inputId());
              assertThat(accepted.createdAt()).isEqualTo(input.createdAt());
              assertThat(accepted.text()).isEqualTo("訂正した文章\n2行目");
            });
    assertThat(display.toString(StandardCharsets.UTF_8))
        .contains("訂正した文章", "2行目")
        .doesNotContain("未確認の認識文");
    assertThatThrownBy(() -> delivery.confirm(input.inputId(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(submitted).hasSize(1);
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly("訂正した文章\n2行目");
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelInvalidCorrectionAndTargetChangeDoNotPrintSubmittedInput() {
    delivery.setConfirmation(true);
    var cancelled = input("cancelled");
    delivery.accept(cancelled);
    assertThat(delivery.cancel(cancelled.inputId())).isTrue();
    assertThatThrownBy(() -> delivery.confirm(cancelled.inputId(), null))
        .isInstanceOf(IllegalArgumentException.class);
    var pending = input("pending");
    delivery.accept(pending);
    assertThatThrownBy(() -> delivery.confirm(pending.inputId(), "/exit"))
        .isInstanceOf(IllegalArgumentException.class);
    try (var scope = client.open()) {
      shell.newConversation();
    }
    assertThatThrownBy(() -> delivery.confirm(pending.inputId(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(submitted).isEmpty();
    assertThat(display.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedAdmissionIsNotPrintedAndSuccessfulRetryIsPrintedOnce() {
    delivery.setConfirmation(true);
    var input = input("original");
    delivery.accept(input);
    reject = true;
    assertThatThrownBy(() -> delivery.confirm(input.inputId(), "corrected"))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(submitted).isEmpty();
    reject = false;
    delivery.confirm(input.inputId(), null);
    assertThat(submitted).extracting(ConversationInput::text).containsExactly("corrected");
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void fullQueueDoesNotPrintRejectedInput() {
    delivery.accept(input("one"));
    delivery.accept(input("two"));
    delivery.accept(input("three"));
    assertThatThrownBy(() -> delivery.accept(input("rejected")))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(submitted).hasSize(3);
    assertThat(display.toString(StandardCharsets.UTF_8)).doesNotContain("rejected");
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void listenerFailureAndUnsubscriptionCannotResubmitAcceptedVoice() {
    delivery.onSubmitted(
        input -> {
          throw new IllegalStateException("display failed");
        });
    var extra = new ArrayList<ConversationInput>();
    var subscription = delivery.onSubmitted(extra::add);
    var first = input("first");
    delivery.accept(first);
    subscription.close();
    delivery.accept(first);
    delivery.accept(input("second"));
    assertThat(submitted).hasSize(2);
    assertThat(extra).containsExactly(first);
  }
}
