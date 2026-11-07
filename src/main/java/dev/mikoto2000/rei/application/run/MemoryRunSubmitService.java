package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.util.function.BiConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * 記憶実行受付のユースケースを実現する。
 *
 * <p>Named memory maintenance over durable Session admission; it writes no conversation turns.
 */
@Service
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class MemoryRunSubmitService {
  /** プロジェクトを解決し、所有するセッションの実行を受け付ける。 */
  private final java.util.function.BiFunction<
          MemoryAdmission, java.util.function.Consumer<AgentRunContext>, AgentRunContext>
      admitMemory;

  private record MemoryAdmission(
      String project, String session, String label, String key, String operation, boolean option) {}

  /** 登録台帳。 */
  private final java.util.function.Consumer<AgentRunContext> registerRun;

  private final java.util.function.Consumer<String> forgetRun;

  /** 実行一覧。 */
  private final RunService runs;

  /** 入力の振り分け。 */
  private final BiConsumer<AgentRunContext, Runnable> submitOperation;

  /** キャンセル。 */
  private final java.util.function.Supplier<Runnable> beginCommand;

  private final java.util.function.BooleanSupplier cancellationRequested;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 待機。 */
  private record SleepOutput(boolean completed, String text) {}

  private final java.util.function.BiFunction<AgentRunContext, Boolean, SleepOutput> runSleep;

  /** 補助。 */
  private final MemoryCommandSupport support;

  /** 要約。 */
  private dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand summary;

  /** 統合。 */
  private dev.mikoto2000.rei.memory.command.MemoryCommand.ConsolidateCommand consolidation;

  /** 実行の受付と状態管理で使用する要約を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSummary(dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand summary) {
    this.summary = summary;
  }

  /** 実行の受付と状態管理で使用する統合を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setConsolidation(
      dev.mikoto2000.rei.memory.command.MemoryCommand.ConsolidateCommand consolidation) {
    this.consolidation = consolidation;
  }

  /** 指定された値や利用先を使って、MemoryRunSubmitServiceを初期化する。 */
  public MemoryRunSubmitService(
      ProjectRegistry projects,
      SessionLifecycle lifecycle,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      CommandCancellationService cancellation,
      AgentEventFactory events,
      AgentEventBus bus,
      SleepService sleep,
      MemoryCommandSupport support) {
    this.registerRun = context -> registry.register(context);
    this.forgetRun = run -> registry.forget(run);
    this.runs = runs;
    this.submitOperation =
        (context, operation) ->
            router.submitOperation(context, operation, work -> runs.execute(context, work));
    this.beginCommand =
        () -> {
          cancellation.begin(Thread.currentThread());
          return cancellation::clear;
        };
    this.cancellationRequested = () -> cancellation.isCancellationRequested();
    this.events = events;
    this.bus = bus;
    this.runSleep =
        (context, preview) -> {
          var report = sleep.sleep(context.conversationId(), context.projectId(), preview);
          if (cancellationRequested.getAsBoolean() || Thread.currentThread().isInterrupted())
            return new SleepOutput(false, null);
          return new SleepOutput(true, support.report(report));
        };
    this.support = support;
    this.admitMemory =
        (request, execution) -> {
          var project =
              projects
                  .resolveById(request.project())
                  .orElseThrow(() -> new ResourceNotFoundException("Project"));
          String fingerprint;
          try {
            fingerprint =
                dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(
                    dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(
                        java.util.List.of(
                            request.operation(),
                            request.project(),
                            request.session(),
                            request.option())));
          } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot fingerprint memory request", error);
          }
          return lifecycle.submit(
              project,
              request.session(),
              request.label(),
              AgentRunContext.RequestSource.WEB,
              AgentRunContext.Mode.EXCLUSIVE,
              false,
              request.key(),
              fingerprint,
              context -> {
                registerRun.accept(context);
                try {
                  submitOperation.accept(context, () -> execution.accept(context));
                } catch (RuntimeException | Error error) {
                  forgetRun.accept(context.runId());
                  throw error;
                }
              });
        };
  }

  /** 実行の受付と状態管理で用いる待機を返す。 */
  public AgentRunContext sleep(String projectId, String sessionId, boolean preview, String key) {
    return submit(
        projectId,
        sessionId,
        preview,
        key,
        "memory.sleep",
        preview ? "Sleep preview" : "Sleep",
        context -> executeSleep(context, preview));
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  public AgentRunContext summarize(
      String projectId, String sessionId, boolean approve, String key) {
    return submit(
        projectId,
        sessionId,
        approve,
        key,
        "memory.summarize",
        "Memory summary",
        context ->
            executeReport(
                context,
                "memory.summarize",
                () -> {
                  if (summary == null)
                    throw new IllegalStateException("Memory summarization is unavailable");
                  return summary.render(approve);
                }));
  }

  /** 実行の受付と状態管理で用いるconsolidateを返す。 */
  public AgentRunContext consolidate(
      String projectId, String sessionId, boolean approve, String key) {
    return submit(
        projectId,
        sessionId,
        approve,
        key,
        "memory.consolidate",
        "Memory consolidation",
        context ->
            executeReport(
                context,
                "memory.consolidate",
                () -> {
                  if (consolidation == null)
                    throw new IllegalStateException("Memory consolidation is unavailable");
                  return consolidation.render(approve);
                }));
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  private AgentRunContext submit(
      String projectId,
      String sessionId,
      boolean option,
      String key,
      String operation,
      String label,
      java.util.function.Consumer<AgentRunContext> execution) {
    if (sessionId == null
        || sessionId.isBlank()
        || sessionId.length() > 512
        || key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("Selected Session and receipt key required");
    return admitMemory.apply(
        new MemoryAdmission(projectId, sessionId, label, key, operation, option), execution);
  }

  /** 依頼された操作を進めるため、レポートを実行する。 */
  private void executeReport(
      AgentRunContext context, String operation, java.util.function.Supplier<String> report) {
    var scope = AgentRunScope.open(context);
    try (scope) {
      Runnable clearCancellation = beginCommand.get();
      try {
        if (cancellationRequested.getAsBoolean()) return;
        bus.publish(events.runStarted(context.runId(), operation, null).withOwnership(context));
        String text = report.get();
        if (cancellationRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
        bus.publish(
            events
                .messageDelta(context.runId(), CredentialRedactor.redact(text))
                .withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context));
      } catch (java.util.concurrent.CancellationException cancelled) {
        runs.cancel(context.runId());
      } finally {
        clearCancellation.run();
      }
    }
  }

  /** 依頼された操作を進めるため、待機を実行する。 */
  private void executeSleep(AgentRunContext context, boolean preview) {
    var scope = AgentRunScope.open(context);
    try (scope) {
      Runnable clearCancellation = beginCommand.get();
      try {
        if (cancellationRequested.getAsBoolean()) return;
        bus.publish(
            events.runStarted(context.runId(), "memory.sleep", null).withOwnership(context));
        support.enabled();
        bus.publish(
            events
                .messageDelta(
                    context.runId(), preview ? "Sleep preview started..." : "Sleep started...")
                .withOwnership(context));
        var report = runSleep.apply(context, preview);
        if (!report.completed()) return;
        bus.publish(events.messageDelta(context.runId(), report.text()).withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context));
      } catch (java.util.concurrent.CancellationException cancelled) {
        runs.cancel(context.runId());
      } finally {
        clearCancellation.run();
      }
    }
  }
}
