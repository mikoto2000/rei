package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.ProjectClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 音声配信のユースケースを実現する。
 *
 * <p>One Shell-owned microphone binding, separate from all other clients and from tool approval.
 */
public final class VoiceDeliveryService {
  /**
   * 音声による対話で受け渡す関連付けを表す。
   *
   * @param client クライアント
   * @param target 対象
   * @param epoch 基準時刻
   */
  private record Binding(ProjectClient client, ConversationTarget target, long epoch) {}

  /** 関連付け。 */
  private volatile Binding binding;

  /** シェル。 */
  private final ShellConversationService shell;

  /** 受信した案内や確認待ち情報の保存先。 */
  private final VoiceReviewInbox inbox;

  /** イベント一覧。 */
  private final VoiceEventPublisher events;

  /** 音声処理の詳細設定。 */
  private final java.util.function.Supplier<VoiceAdvancedOptions> advanced;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 停止済み。 */
  private final Map<UUID, ConversationInput> stopped = new LinkedHashMap<>();

  /** 受付済み通知の受け取り先一覧。 */
  private final java.util.concurrent.CopyOnWriteArrayList<
          java.util.function.Consumer<ConversationInput>>
      submittedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** 音声による対話に必要な購読を扱う。 */
  public interface Subscription extends AutoCloseable {
    /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    void close();
  }

  /**
   * 通知に応じて状態や表示を更新するため、受付済みを受け付ける。
   *
   * <p>Shell-only presentation observer; transcripts never enter the diagnostic publisher. May run
   * on the agent worker before accept/confirm returns. Listeners must only render and must not call
   * back into delivery, selection or input admission.
   */
  public Subscription onSubmitted(java.util.function.Consumer<ConversationInput> listener) {
    submittedListeners.add(Objects.requireNonNull(listener));
    return () -> submittedListeners.remove(listener);
  }

  /** 音声による対話に関する受付済みを処理する。 */
  private void submitted(ConversationInput input) {
    for (var listener : submittedListeners) {
      try {
        listener.accept(input);
      } catch (RuntimeException ignored) {
        // Display failures cannot reject or retry an already admitted conversation input.
      }
    }
  }

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public VoiceDeliveryService(
      ShellConversationService shell, Clock clock, VoiceEventPublisher events) {
    this(shell, clock, events, VoiceAdvancedOptions::defaults);
  }

  /** 指定された値や利用先を使って、VoiceDeliveryServiceを初期化する。 */
  public VoiceDeliveryService(
      ShellConversationService shell,
      Clock clock,
      VoiceEventPublisher events,
      java.util.function.Supplier<VoiceAdvancedOptions> advanced) {
    this.advanced = Objects.requireNonNull(advanced);
    this.clock = Objects.requireNonNull(clock);
    this.shell = Objects.requireNonNull(shell);
    this.events = Objects.requireNonNull(events);
    inbox = new VoiceReviewInbox(this::dispatch, i -> targetIsCurrent(i.target()), clock);
  }

  /** 後続の照会で対応する対象を見つけられるよう、音声配信サービスを関連付ける。 */
  public void bind(ProjectClient client, ConversationTarget target) {
    if (client == null || target == null || !shell.isSelected(client, target))
      throw new IllegalStateException("Current Shell client/Session required");
    var previous = binding;
    if (previous != null && (previous.client() != client || !previous.target().equals(target)))
      inbox.clear();
    binding = new Binding(client, target, client.selectionEpoch());
  }

  /** 音声による対話において、対象該当現在に該当するか判定する。 */
  public boolean targetIsCurrent(ConversationTarget target) {
    var selected = binding;
    return selected != null
        && selected.target().equals(target)
        && selected.epoch() == selected.client().selectionEpoch()
        && shell.isSelected(selected.client(), target);
  }

  /** 音声による対話において、owns実行に該当するか判定する。 */
  public boolean ownsRun(dev.mikoto2000.rei.core.chat.AgentRunContext context) {
    var selected = binding;
    return selected != null
        && context.voiceInput()
        && Objects.equals(context.projectId(), selected.target().project().id())
        && context
            .projectRoot()
            .equals(selected.target().project().root().toAbsolutePath().normalize())
        && Objects.equals(context.conversationId(), selected.target().sessionId())
        && targetIsCurrent(selected.target())
        && shell.ownsRun(selected.client(), context);
  }

  /** 音声による対話に関する実行先への引き渡しを処理する。 */
  private void dispatch(ConversationInput input) {
    var selected = binding;
    if (selected == null || !selected.target().equals(input.target()))
      throw new IllegalStateException("Voice target changed");
    synchronized (selected.client()) {
      if (!targetIsCurrent(input.target()))
        throw new IllegalStateException("Voice selection generation changed");
      synchronized (stopped) {
        stopped
            .values()
            .removeIf(prior -> prior.createdAt().isBefore(clock.instant().minusSeconds(120)));
        var prior = stopped.get(input.inputId());
        if (prior != null) {
          if (!prior.equals(input)) throw new IllegalArgumentException("Conflicting stop identity");
          return;
        }
        if (advanced.get().interruptEnabled()
            && input.text().strip().replaceFirst("[。.!！]+$", "").equals("実行を停止")) {
          if (stopped.size() >= 4096)
            throw new IllegalStateException("Stop deduplication capacity reached");
          // Identity and selection are checked before any cancellation side effect.
          int count = shell.cancelSelectedActive(selected.client(), input);
          stopped.put(input.inputId(), input);
          events.publish(
              VoiceEventPublisher.Type.INTERRUPT_RESULT,
              count > 0 ? "cancelled" : "no owned active run");
          return;
        }
      }
      shell.submitSelectedVoice(selected.client(), input, () -> submitted(input));
    }
  }

  /** 要求を後続の処理へ引き渡すため、音声配信サービスを受け付ける。 */
  public void accept(ConversationInput input) {
    inbox.accept(input);
    if (inbox.confirmation())
      events.publish(VoiceEventPublisher.Type.REVIEW_REQUIRED, input.inputId().toString());
  }

  /** 条件を満たさない操作を拒否するため、レビューの前提条件を確認する。 */
  public void requireReview(ConversationInput input) {
    inbox.accept(input, true);
    events.publish(VoiceEventPublisher.Type.REVIEW_REQUIRED, input.inputId().toString());
  }

  /** 音声による対話で使用する確認を設定する。 */
  public void setConfirmation(boolean value) {
    inbox.setConfirmation(value);
  }

  /** 音声による対話において、確認に該当するか判定する。 */
  public boolean confirmation() {
    return inbox.confirmation();
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public List<ConversationInput> pending() {
    return inbox.pending();
  }

  /** 対象の実行をキャンセルする。 */
  public boolean cancel(UUID id) {
    return inbox.cancel(id);
  }

  /** 音声による対話に関するconfirmを処理する。 */
  public void confirm(UUID id, String correction) {
    inbox.confirm(id, correction);
  }

  /** 保持している情報を初期状態へ戻すため、音声配信サービスを消去する。 */
  public void clear() {
    inbox.clear();
  }
}
