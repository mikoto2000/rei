package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.InputSource;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 音声による対話に必要な音声レビューInboxを扱う。
 *
 * <p>Recognition confirmation is separate from tool approval. Pending speech stays only in bounded
 * memory.
 */
public final class VoiceReviewInbox {
  /** 許容件数。 */
  public static final int CAPACITY = 3;

  /** 保持期間。 */
  public static final Duration RETENTION = Duration.ofMinutes(2);

  /** 実行先への引き渡し。 */
  private final Consumer<ConversationInput> dispatch;

  /** 選択中。 */
  private final Predicate<ConversationInput> selected;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 待機中。 */
  private final Map<UUID, ConversationInput> pending = new LinkedHashMap<>();

  /** 確認。 */
  private boolean confirmation;

  /** 指定された値や利用先を使って、VoiceReviewInboxを初期化する。 */
  public VoiceReviewInbox(
      Consumer<ConversationInput> dispatch, Predicate<ConversationInput> selected, Clock clock) {
    this.dispatch = Objects.requireNonNull(dispatch);
    this.selected = Objects.requireNonNull(selected);
    this.clock = Objects.requireNonNull(clock);
  }

  /** 音声による対話で使用する確認を設定する。 */
  public synchronized void setConfirmation(boolean value) {
    confirmation = value;
  }

  /** 音声による対話において、確認に該当するか判定する。 */
  public synchronized boolean confirmation() {
    return confirmation;
  }

  /** 音声による対話において、有効に該当するか判定する。 */
  private boolean valid(ConversationInput input) {
    var now = clock.instant();
    return !input.createdAt().isAfter(now)
        && !input.createdAt().isBefore(now.minus(RETENTION))
        && selected.test(input);
  }

  /** 音声による対話に関する不要な情報の整理を処理する。 */
  private void prune() {
    pending.values().removeIf(input -> !valid(input));
  }

  /** 要求を後続の処理へ引き渡すため、音声レビューInboxを受け付ける。 */
  public synchronized void accept(ConversationInput input) {
    accept(input, false);
  }

  /** 要求を後続の処理へ引き渡すため、音声レビューInboxを受け付ける。 */
  public synchronized void accept(ConversationInput input, boolean forceConfirmation) {
    if (input.source() != InputSource.VOICE)
      throw new IllegalArgumentException("Recognition inbox accepts VOICE only");
    prune();
    if (!valid(input))
      throw new IllegalStateException("Voice target changed or recognition expired");
    if (!confirmation && !forceConfirmation) {
      dispatch.accept(input);
      return;
    }
    var previous = pending.get(input.inputId());
    if (previous != null) {
      if (!previous.equals(input))
        throw new IllegalArgumentException("Conflicting recognition identity");
      return;
    }
    if (pending.size() >= CAPACITY)
      throw new IllegalStateException("Recognition confirmation queue is full (3)");
    pending.put(input.inputId(), input);
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public synchronized List<ConversationInput> pending() {
    prune();
    return List.copyOf(pending.values());
  }

  /** 対象の実行をキャンセルする。 */
  public synchronized boolean cancel(UUID id) {
    prune();
    return pending.remove(id) != null;
  }

  /** 保持している情報を初期状態へ戻すため、音声レビューInboxを消去する。 */
  public synchronized void clear() {
    pending.clear();
  }

  /** 音声による対話に関するconfirmを処理する。 */
  public synchronized void confirm(UUID id, String correction) {
    prune();
    var input = pending.get(id);
    if (input == null)
      throw new IllegalArgumentException(
          "Recognition is missing, cancelled, expired or its target changed");
    String text =
        correction == null
            ? input.text()
            : SpeechResultFilter.filter(correction)
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "Invalid voice correction; commands are not allowed"));
    var corrected =
        new ConversationInput(
            input.inputId(), InputSource.VOICE, input.target(), text, input.createdAt());
    pending.put(id, corrected);
    dispatch.accept(corrected);
    pending.remove(id);
  }
}
