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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/** Named memory maintenance over durable Session admission; it writes no conversation turns. */
@Service
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class MemoryRunSubmitService {
  private final ProjectRegistry projects;
  private final SessionLifecycle lifecycle;
  private final RunRegistry registry;
  private final RunService runs;
  private final ConversationInputRouter router;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventBus bus;
  private final SleepService sleep;
  private final MemoryCommandSupport support;
  private dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand summary;
  private dev.mikoto2000.rei.memory.command.MemoryCommand.ConsolidateCommand consolidation;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSummary(dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand summary) {
    this.summary = summary;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setConsolidation(
      dev.mikoto2000.rei.memory.command.MemoryCommand.ConsolidateCommand consolidation) {
    this.consolidation = consolidation;
  }

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
    this.projects = projects;
    this.lifecycle = lifecycle;
    this.registry = registry;
    this.runs = runs;
    this.router = router;
    this.cancellation = cancellation;
    this.events = events;
    this.bus = bus;
    this.sleep = sleep;
    this.support = support;
  }

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
    var project =
        projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    String fingerprint;
    try {
      fingerprint =
          dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(
              dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(
                  java.util.List.of(operation, projectId, sessionId, option)));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot fingerprint memory request", error);
    }
    return lifecycle.submit(
        project,
        sessionId,
        label,
        AgentRunContext.RequestSource.WEB,
        AgentRunContext.Mode.EXCLUSIVE,
        false,
        key,
        fingerprint,
        context -> {
          registry.register(context);
          try {
            router.submitOperation(
                context, () -> execution.accept(context), work -> runs.execute(context, work));
          } catch (RuntimeException | Error error) {
            registry.forget(context.runId());
            throw error;
          }
        });
  }

  private void executeReport(
      AgentRunContext context, String operation, java.util.function.Supplier<String> report) {
    try (var scope = AgentRunScope.open(context)) {
      cancellation.begin(Thread.currentThread());
      try {
        if (cancellation.isCancellationRequested()) return;
        bus.publish(events.runStarted(context.runId(), operation, null).withOwnership(context));
        String text = report.get();
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted())
          return;
        bus.publish(
            events
                .messageDelta(context.runId(), CredentialRedactor.redact(text))
                .withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context));
      } catch (java.util.concurrent.CancellationException cancelled) {
        runs.cancel(context.runId());
      } finally {
        cancellation.clear();
      }
    }
  }

  private void executeSleep(AgentRunContext context, boolean preview) {
    try (var scope = AgentRunScope.open(context)) {
      cancellation.begin(Thread.currentThread());
      try {
        if (cancellation.isCancellationRequested()) return;
        bus.publish(
            events.runStarted(context.runId(), "memory.sleep", null).withOwnership(context));
        support.enabled();
        bus.publish(
            events
                .messageDelta(
                    context.runId(), preview ? "Sleep preview started..." : "Sleep started...")
                .withOwnership(context));
        var report = sleep.sleep(context.conversationId(), context.projectId(), preview);
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted())
          return;
        bus.publish(
            events.messageDelta(context.runId(), support.report(report)).withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context));
      } catch (java.util.concurrent.CancellationException cancelled) {
        runs.cancel(context.runId());
      } finally {
        cancellation.clear();
      }
    }
  }
}
