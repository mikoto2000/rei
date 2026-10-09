package dev.mikoto2000.rei.application.input;

import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.*;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.*;

/** Bounded deduplication and voice admission over the existing Session and Run queue. */
public final class ConversationInputGateway {
  private record Accepted(ConversationInput input, AgentRunContext context, Instant acceptedAt) {}
  private final SessionLifecycle lifecycle;
  private final BiConsumer<AgentRunContext, String> dispatch;
  private final ConversationInputRouter router;
  private final Clock clock;
  private final int ledgerLimit;
  private final Duration retention;
  private final LinkedHashMap<UUID, Accepted> accepted = new LinkedHashMap<>();
  private Predicate<AgentRunContext> cancel;
  public ConversationInputGateway(SessionLifecycle lifecycle, BiConsumer<AgentRunContext, String> dispatch,
      ConversationInputRouter router, Clock clock) {
    this(lifecycle, dispatch, router, clock, 4096, Duration.ofMinutes(30));
  }
  public ConversationInputGateway(SessionLifecycle lifecycle, BiConsumer<AgentRunContext, String> dispatch,
      ConversationInputRouter router, Clock clock, int ledgerLimit, Duration retention) {
    this.lifecycle=Objects.requireNonNull(lifecycle); this.dispatch=Objects.requireNonNull(dispatch);
    this.router=router; this.clock=Objects.requireNonNull(clock);
    if (ledgerLimit < 1 || retention == null || retention.isNegative() || retention.isZero())
      throw new IllegalArgumentException("Positive ledger bounds required");
    this.ledgerLimit=ledgerLimit; this.retention=retention;
    this.cancel=context -> router != null && router.cancelQueued(context.runId());
  }
  public synchronized void onCancel(Predicate<AgentRunContext> cancellation) {
    cancel=Objects.requireNonNull(cancellation);
  }
  public synchronized AgentRunContext submit(ConversationInput input, AgentRunContext.Mode mode) {
    return submit(input,mode,run->{},run->{});
  }
  public synchronized AgentRunContext submit(ConversationInput input,AgentRunContext.Mode mode,
      Consumer<AgentRunContext> beforeDispatch,Consumer<AgentRunContext> failedDispatch) {
    Objects.requireNonNull(input);
    if (mode == null) mode=AgentRunContext.Mode.EXCLUSIVE;
    if (input.source() == InputSource.VOICE && mode != AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Voice uses exclusive agent execution");
    if (input.source() == InputSource.VOICE && router == null)
      throw new IllegalStateException("Voice requires the existing Run queue");
    prune();
    var prior=accepted.get(input.inputId());
    if (prior != null) {
      if (!prior.input().equals(input) || prior.context().mode() != mode)
        throw new IllegalArgumentException("Input ID already belongs to another payload");
      return prior.context();
    }
    if (accepted.size() >= ledgerLimit) throw new RejectedExecutionException("Input deduplication capacity reached");
    if (input.source() == InputSource.VOICE && accepted.values().stream()
        .filter(entry -> entry.input().source() == InputSource.VOICE && queued(entry)).count() >= 3)
      throw new RejectedExecutionException("Voice pending capacity reached");
    if (!input.createdAt().isAfter(clock.instant().minus(retention)))
      throw new IllegalArgumentException("Input has expired");
    var context=lifecycle.submit(input.target().project(), input.target().sessionId(), input.text(),
        AgentRunContext.RequestSource.SHELL, mode, input.source()==InputSource.VOICE, run -> {
          beforeDispatch.accept(run);
          try{dispatch.accept(run,input.text());}catch(RuntimeException failed){failedDispatch.accept(run);throw failed;}
        });
    accepted.put(input.inputId(), new Accepted(input, context, clock.instant()));
    return context;
  }
  public synchronized List<ConversationInput> pending(ConversationTarget target) {
    return accepted.values().stream().filter(entry -> entry.input().source() == InputSource.VOICE)
        .filter(entry -> sameTarget(entry, target) && queued(entry)).map(Accepted::input).toList();
  }
  public synchronized boolean cancelPending(ConversationTarget target, UUID inputId) {
    var entry=accepted.get(inputId);
    return entry != null && sameTarget(entry,target)
        && queued(entry) && cancel.test(entry.context());
  }
  private boolean sameTarget(Accepted entry, ConversationTarget target) {
    return Objects.equals(entry.context().projectId(), target.project().id())
        && Objects.equals(entry.context().conversationId(), target.sessionId())
        && entry.context().projectRoot().equals(target.project().root().toAbsolutePath().normalize());
  }
  private boolean queued(Accepted entry) {
    return router != null && router.isQueued(entry.context().projectId(), entry.context().runId());
  }
  private void prune() {
    var cutoff=clock.instant().minus(retention);
    accepted.values().removeIf(entry -> !entry.acceptedAt().isAfter(cutoff)
        && (router == null || !router.containsRun(entry.context().projectId(),entry.context().runId())));
  }
}
