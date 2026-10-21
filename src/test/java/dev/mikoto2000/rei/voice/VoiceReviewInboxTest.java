package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 音声レビューInboxの結果と状態の契約を検証するテスト。 */
class VoiceReviewInboxTest {
  /** 検証に使う対象。 */
  final ConversationTarget target =
      new ConversationTarget(
          new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");

  /** {@code input} の条件を再現するための補助情報を返す。 */
  ConversationInput input(String text) {
    return new ConversationInput(
        UUID.randomUUID(), InputSource.VOICE, target, text, Instant.parse("2026-10-09T08:00:00Z"));
  }

  /** 自動動作モードについて、既存の受付窓口パスを維持することを確認する。 */
  @Test
  void automaticModeKeepsExistingGatewayPath() {
    var submitted = new ArrayList<ConversationInput>();
    var inbox =
        new VoiceReviewInbox(
            submitted::add, i -> true, Clock.fixed(input("hello").createdAt(), ZoneOffset.UTC));
    var input = input("こんにちは");
    inbox.accept(input);
    assertThat(submitted).containsExactly(input);
    assertThat(inbox.pending()).isEmpty();
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void confirmationModeStoresAtMostThreeWithoutSendingAndCorrectionRetainsIdentity() {
    var submitted = new ArrayList<ConversationInput>();
    var inbox =
        new VoiceReviewInbox(
            submitted::add, i -> true, Clock.fixed(input("hello").createdAt(), ZoneOffset.UTC));
    inbox.setConfirmation(true);
    var input = input("本性入力");
    inbox.accept(input);
    inbox.accept(input("two"));
    inbox.accept(input("three"));
    assertThatThrownBy(() -> inbox.accept(input("four"))).isInstanceOf(IllegalStateException.class);
    assertThat(submitted).isEmpty();
    assertThat(inbox.pending()).hasSize(3);
    inbox.confirm(input.inputId(), "音声入力");
    assertThat(submitted).hasSize(1);
    assertThat(submitted.getFirst().text()).isEqualTo("音声入力");
    assertThat(submitted.getFirst().inputId()).isEqualTo(input.inputId());
    assertThat(submitted.getFirst().source()).isEqualTo(InputSource.VOICE);
    assertThat(submitted.getFirst().target()).isEqualTo(target);
    assertThat(inbox.pending()).hasSize(2);
    assertThatThrownBy(() -> inbox.confirm(input.inputId(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void slashCorrectionCannotBecomeCliOrDispatchAndCanBeCancelled() {
    var submitted = new ArrayList<ConversationInput>();
    var inbox =
        new VoiceReviewInbox(
            submitted::add, i -> true, Clock.fixed(input("hello").createdAt(), ZoneOffset.UTC));
    inbox.setConfirmation(true);
    var input = input("こんにちは");
    inbox.accept(input);
    assertThatThrownBy(() -> inbox.confirm(input.inputId(), "/approval approve all"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(submitted).isEmpty();
    assertThat(inbox.cancel(input.inputId())).isTrue();
    assertThat(inbox.cancel(input.inputId())).isFalse();
  }

  /** 音声による対話で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void targetChangeDropsReviewAndPreventsAutomaticOrConfirmedStaleSubmission() {
    var selected = new AtomicBoolean(true);
    var submitted = new ArrayList<ConversationInput>();
    var inbox =
        new VoiceReviewInbox(
            submitted::add,
            i -> selected.get(),
            Clock.fixed(input("hello").createdAt(), ZoneOffset.UTC));
    inbox.setConfirmation(true);
    var input = input("hello");
    inbox.accept(input);
    selected.set(false);
    assertThatThrownBy(() -> inbox.confirm(input.inputId(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(inbox.pending()).isEmpty();
    inbox.setConfirmation(false);
    assertThatThrownBy(() -> inbox.accept(input("new"))).isInstanceOf(IllegalStateException.class);
    assertThat(submitted).isEmpty();
  }

  /** 音声による対話で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void expiredRecognitionIsNeverConfirmed() {
    var submitted = new ArrayList<ConversationInput>();
    var old = input("hello");
    var inbox =
        new VoiceReviewInbox(
            submitted::add,
            i -> true,
            Clock.fixed(old.createdAt().plus(Duration.ofMinutes(3)), ZoneOffset.UTC));
    inbox.setConfirmation(true);
    assertThatThrownBy(() -> inbox.accept(old)).isInstanceOf(IllegalStateException.class);
    assertThat(submitted).isEmpty();
  }

  /** 失敗実行先への引き渡しについて、レビューを維持することを確認する。 */
  @Test
  void failedDispatchKeepsReviewForRetryWithoutDuplicateSuccessfulSubmission() {
    var submitted = new ArrayList<ConversationInput>();
    var fail = new AtomicBoolean(true);
    var inbox =
        new VoiceReviewInbox(
            i -> {
              if (fail.get()) throw new IllegalStateException("busy");
              submitted.add(i);
            },
            i -> true,
            Clock.fixed(input("hello").createdAt(), ZoneOffset.UTC));
    inbox.setConfirmation(true);
    var input = input("hello");
    inbox.accept(input);
    assertThatThrownBy(() -> inbox.confirm(input.inputId(), "corrected"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(inbox.pending()).hasSize(1);
    assertThat(inbox.pending().getFirst().text()).isEqualTo("corrected");
    fail.set(false);
    inbox.confirm(input.inputId(), null);
    assertThat(submitted).hasSize(1);
    assertThat(submitted.getFirst().text()).isEqualTo("corrected");
    assertThat(submitted.getFirst().inputId()).isEqualTo(input.inputId());
    assertThat(inbox.pending()).isEmpty();
  }
}
