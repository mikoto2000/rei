package dev.mikoto2000.rei.application.input;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 会話入力の入力を共通の受付経路へつなぐ。
 *
 * <p>Bounded deduplication and voice admission over the existing Session and Run queue.
 */
public final class ConversationInputGateway {
  /**
   * 会話入力の受付で受け渡す受付済みを表す。
   *
   * @param input 入力
   * @param context 文脈
   * @param acceptedAt 受付済み日時
   */
  private record Accepted(ConversationInput input, AgentRunContext context, Instant acceptedAt) {}

  /** 受付から終了までの管理。 */
  private final SessionLifecycle lifecycle;

  /** 実行先への引き渡し。 */
  private final BiConsumer<AgentRunContext, String> dispatch;

  /** 入力の振り分け。 */
  private final ConversationInputRouter router;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 記録台帳上限。 */
  private final int ledgerLimit;

  /** 保持期間。 */
  private final Duration retention;

  /** 受付済み。 */
  private final LinkedHashMap<UUID, Accepted> accepted = new LinkedHashMap<>();

  /** キャンセル。 */
  private Predicate<AgentRunContext> cancel;

  /** 会話入力の受付を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationInputGateway(
      SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch,
      ConversationInputRouter router,
      Clock clock) {
    this(lifecycle, dispatch, router, clock, 4096, Duration.ofMinutes(30));
  }

  /** 指定された値や利用先を使って、ConversationInputGatewayを初期化する。 */
  public ConversationInputGateway(
      SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch,
      ConversationInputRouter router,
      Clock clock,
      int ledgerLimit,
      Duration retention) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.dispatch = Objects.requireNonNull(dispatch);
    this.router = router;
    this.clock = Objects.requireNonNull(clock);
    if (ledgerLimit < 1 || retention == null || retention.isNegative() || retention.isZero())
      throw new IllegalArgumentException("Positive ledger bounds required");
    this.ledgerLimit = ledgerLimit;
    this.retention = retention;
    this.cancel = context -> router != null && router.cancelQueued(context.runId());
  }

  /** 通知に応じて状態や表示を更新するため、キャンセルを受け付ける。 */
  public synchronized void onCancel(Predicate<AgentRunContext> cancellation) {
    cancel = Objects.requireNonNull(cancellation);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public synchronized AgentRunContext submit(ConversationInput input, AgentRunContext.Mode mode) {
    return submit(input, mode, run -> {}, run -> {});
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public synchronized AgentRunContext submit(
      ConversationInput input,
      AgentRunContext.Mode mode,
      Consumer<AgentRunContext> beforeDispatch,
      Consumer<AgentRunContext> failedDispatch) {
    return submit(input, mode, beforeDispatch, failedDispatch, () -> {});
  }

  /**
   * 依頼を受け付け、実行を担当する処理へ引き渡す。
   *
   * <p>Render once after admission, before the new run can produce output. Duplicate IDs and
   * rejected enqueue never notify. May run on the worker before submit returns: the presentation
   * callback must not reenter admission/selection APIs.
   */
  public synchronized AgentRunContext submit(
      ConversationInput input, AgentRunContext.Mode mode, Runnable afterAccepted) {
    return submit(input, mode, run -> {}, run -> {}, afterAccepted);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public synchronized AgentRunContext submit(
      ConversationInput input,
      AgentRunContext.Mode mode,
      Consumer<AgentRunContext> beforeDispatch,
      Consumer<AgentRunContext> failedDispatch,
      Runnable afterAccepted) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(afterAccepted);
    if (mode == null) mode = AgentRunContext.Mode.EXCLUSIVE;
    if (input.source() == InputSource.VOICE && mode != AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Voice uses exclusive agent execution");
    if (input.source() == InputSource.VOICE && router == null)
      throw new IllegalStateException("Voice requires the existing Run queue");
    prune();
    var prior = accepted.get(input.inputId());
    if (prior != null) {
      if (!prior.input().equals(input) || prior.context().mode() != mode)
        throw new IllegalArgumentException("Input ID already belongs to another payload");
      return prior.context();
    }
    if (accepted.size() >= ledgerLimit)
      throw new RejectedExecutionException("Input deduplication capacity reached");
    if (input.source() == InputSource.VOICE
        && accepted.values().stream()
                .filter(entry -> entry.input().source() == InputSource.VOICE && queued(entry))
                .count()
            >= 3) throw new RejectedExecutionException("Voice pending capacity reached");
    if (!input.createdAt().isAfter(clock.instant().minus(retention)))
      throw new IllegalArgumentException("Input has expired");
    var notifyAccepted = acceptanceNotification(afterAccepted);
    var context =
        lifecycle.submit(
            input.target().project(),
            input.target().sessionId(),
            input.text(),
            AgentRunContext.RequestSource.SHELL,
            mode,
            input.source() == InputSource.VOICE,
            run -> {
              try (var observer =
                  router == null
                      ? (ConversationInputRouter.Subscription) () -> {}
                      : router.beforeExecution(run.runId(), notifyAccepted)) {
                beforeDispatch.accept(run);
                try {
                  dispatch.accept(run, input.text());
                } catch (RuntimeException failed) {
                  failedDispatch.accept(run);
                  throw failed;
                }
              }
            });
    accepted.put(input.inputId(), new Accepted(input, context, clock.instant()));
    notifyAccepted.run();
    return context;
  }

  /** 会話入力の受付で用いる受け入れ条件通知を返す。 */
  private Runnable acceptanceNotification(Runnable listener) {
    return new Runnable() {
      /** 通知済み。 */
      private boolean notified;

      /** 会話入力の受付の操作を実行する。 */
      @Override
      public synchronized void run() {
        if (notified) return;
        notified = true;
        // The runner must wait for the display even when the submitting thread wins
        // this race. A CAS alone could let its answer overtake the unfinished frame.
        try {
          listener.run();
        } catch (RuntimeException error) {
          org.slf4j.LoggerFactory.getLogger(ConversationInputGateway.class)
              .warn("Accepted input listener failed ({})", error.getClass().getSimpleName());
        }
      }
    };
  }

  /**
   * 会話入力の受付において、Novelかどうかを判定する。
   *
   * <p>Read-only identity check for a control input before any cancellation side effect.
   */
  public synchronized boolean isNovel(ConversationInput input) {
    Objects.requireNonNull(input);
    prune();
    var prior = accepted.get(input.inputId());
    if (prior == null) return true;
    if (!prior.input().equals(input))
      throw new IllegalArgumentException("Input ID already belongs to another payload");
    return false;
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public synchronized List<ConversationInput> pending(ConversationTarget target) {
    return accepted.values().stream()
        .filter(entry -> entry.input().source() == InputSource.VOICE)
        .filter(entry -> sameTarget(entry, target) && queued(entry))
        .map(Accepted::input)
        .toList();
  }

  /** 利用者が不要とした実行を止めるため、待機中をキャンセルする。 */
  public synchronized boolean cancelPending(ConversationTarget target, UUID inputId) {
    var entry = accepted.get(inputId);
    return entry != null
        && sameTarget(entry, target)
        && queued(entry)
        && cancel.test(entry.context());
  }

  /** 会話入力の受付において、同一対象に該当するか判定する。 */
  private boolean sameTarget(Accepted entry, ConversationTarget target) {
    return Objects.equals(entry.context().projectId(), target.project().id())
        && Objects.equals(entry.context().conversationId(), target.sessionId())
        && entry
            .context()
            .projectRoot()
            .equals(target.project().root().toAbsolutePath().normalize());
  }

  /** 会話入力の受付において、待機中に該当するか判定する。 */
  private boolean queued(Accepted entry) {
    return router != null && router.isQueued(entry.context().projectId(), entry.context().runId());
  }

  /** 会話入力の受付に関する不要な情報の整理を処理する。 */
  private void prune() {
    var cutoff = clock.instant().minus(retention);
    accepted
        .values()
        .removeIf(
            entry ->
                !entry.acceptedAt().isAfter(cutoff)
                    && (router == null
                        || !router.containsRun(
                            entry.context().projectId(), entry.context().runId())));
  }
}
