package dev.mikoto2000.rei.core.chat;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 会話の実行に必要な利用者追加入力実行待ちを扱う。
 *
 * <p>A run-owned mailbox. Closing and accepting input share one linearization point.
 */
public final class UserInterventionQueue {
  /**
   * 会話の実行で受け渡す項目を表す。
   *
   * @param id 識別子
   * @param text テキスト
   */
  public record Entry(String id, String text) {}

  /** 待機中。 */
  private final ArrayDeque<Entry> pending = new ArrayDeque<>();

  /** 受信。 */
  private final java.util.function.Consumer<Entry> received;

  /** 完了済み。 */
  private boolean finished;

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UserInterventionQueue() {
    this(entry -> {});
  }

  /** 指定された値や利用先を使って、UserInterventionQueueを初期化する。 */
  public UserInterventionQueue(java.util.function.Consumer<Entry> received) {
    this.received = received;
  }

  /** 会話の実行において、受付に該当するか判定する。 */
  public synchronized boolean offer(String text) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (text == null || text.isBlank() || text.length() > 16384)
      throw new IllegalArgumentException("Invalid guidance");
    if (finished || pending.size() >= 32) return false;
    var entry =
        new Entry(java.util.UUID.randomUUID().toString(), java.util.Objects.requireNonNull(text));
    pending.addLast(entry);
    received.accept(entry);
    return true;
  }

  /** 会話の実行で用いる残っている対象の処理を返す。 */
  public synchronized List<String> drain() {
    return drainEntries().stream().map(Entry::text).toList();
  }

  /** 会話の実行で用いる残っている対象の処理項目一覧を返す。 */
  public synchronized List<Entry> drainEntries() {
    var result = List.copyOf(pending);
    pending.clear();
    return result;
  }

  /** 会話の実行において、完了条件付き空に該当するか判定する。 */
  public synchronized boolean finishIfEmpty() {
    if (!pending.isEmpty()) return false;
    finished = true;
    return true;
  }

  /** 会話の実行で用いる破棄および完了を返す。 */
  public synchronized int discardAndFinish() {
    int count = pending.size();
    pending.clear();
    finished = true;
    return count;
  }
}
