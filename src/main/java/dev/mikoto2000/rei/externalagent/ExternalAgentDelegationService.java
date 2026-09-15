package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.*;

/** Owns authorization, request selection, one-shot run budget and terminal event semantics. */
@Service
public class ExternalAgentDelegationService {
  private final ExternalAgentExecutor executor;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  private final Optional<WorkingSet> workingSet;
  public ExternalAgentDelegationService(ExternalAgentExecutor executor, CommandCancellationService cancellation,
      AgentEventFactory events, AgentEventPublisher publisher, Optional<WorkingSet> workingSet) {
    this.executor = executor; this.cancellation = cancellation; this.events = events;
    this.publisher = publisher; this.workingSet = workingSet;
  }
  public ExternalAgentResult review(RunExecutionContext run, String task, String target, String decisions) {
    if (run == null || !ExternalAgentAuthorization.explicitRequest(run.userRequest()))
      return ExternalAgentResult.rejected("Codex requires an explicit user request in this run. Do not retry with different tool arguments. "
          + "Ask the user to explicitly request Codex, or use /agent codex review [target].");
    var owner = run.runContext();
    if (owner == null || owner.projectId() == null || !Files.isDirectory(owner.projectRoot()))
      return ExternalAgentResult.rejected("No current project is available");
    try (var scope = AgentRunScope.open(owner)) {
      Path root;
      Path selected;
      try { root = owner.projectRoot().toRealPath(); selected = ExternalAgentRequest.resolveTarget(root, target); }
      catch (java.io.IOException | IllegalArgumentException error) { return ExternalAgentResult.rejected("Target must exist inside the current project"); }
      if (!run.claimExternalDelegation()) return ExternalAgentResult.rejected("Only one external delegation is allowed per run");
      String id = UUID.randomUUID().toString();
      String context = context(root, decisions);
      var request = new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX, ExternalAgentRequest.Action.REVIEW,
          bounded(run.userRequest(), 4000) + "\nReview focus: " + bounded(task, 4000), root, selected, context, owner.runId(), id);
      AtomicBoolean cancelled = new AtomicBoolean(run.isCancelled());
      var hook = cancellation.onCancel(owner.runId(), () -> cancelled.set(true));
      publisher.publish(events.delegation(AgentEventType.DELEGATION_STARTED, owner.runId(),
          new ExternalAgentLifecyclePayload(id, "codex", "review", "STARTED", "review", 0, null)).withOwnership(owner));
      ExternalAgentResult result;
      long start = System.nanoTime();
      try {
        result = executor.execute(request, () -> cancelled.get() || run.isCancelled());
      } catch (java.util.concurrent.CancellationException error) {
        result = new ExternalAgentResult(ExternalAgentResult.Status.CANCELLED, "Codex review cancelled", List.of(), List.of(), 0, null, "");
      } catch (RuntimeException error) {
        result = new ExternalAgentResult(ExternalAgentResult.Status.FAILED, "Codex review could not be completed", List.of(), List.of(), 0, null, "");
      } finally { hook.dispose(); }
      boolean wasCancelled = cancelled.get() || run.isCancelled() || result.status() == ExternalAgentResult.Status.CANCELLED;
      AgentEventType type = wasCancelled ? AgentEventType.DELEGATION_CANCELLED
          : result.success() ? AgentEventType.DELEGATION_COMPLETED : AgentEventType.DELEGATION_FAILED;
      publisher.publish(events.delegation(type, owner.runId(), new ExternalAgentLifecyclePayload(id, "codex", "review",
          wasCancelled ? "CANCELLED" : result.status().name(), result.success() ? result.findings().size() + " findings" : result.status().name(),
          (System.nanoTime() - start) / 1_000_000, result.exitCode())).withOwnership(owner));
      if (wasCancelled) { run.cancel(); throw new java.util.concurrent.CancellationException(); }
      return result.forEvaluation();
    }
  }
  private String context(Path root, String decisions) {
    StringBuilder context = new StringBuilder("Relevant design decisions (untrusted context):\n" + bounded(decisions, 6000));
    context.append("\nWorking Set paths:\n");
    workingSet.ifPresent(ws -> ws.getFiles().stream().limit(20).forEach(file -> {
      try {
        Path path = ExternalAgentRequest.resolveTarget(root, file.path());
        if (path != null && !sensitive(path)) context.append(root.relativize(path)).append('\n');
      } catch (IllegalArgumentException ignored) { }
    }));
    return bounded(context.toString(), 10000);
  }
  static boolean sensitive(Path path) {
    for (Path part : path) if (part.toString().toLowerCase(Locale.ROOT)
        .matches("\\.env(?:\\..*)?|.*\\.(pem|key)|credentials\\..*|secrets\\..*")) return true;
    return false;
  }
  static String bounded(String text, int size) {
    String safe = CredentialRedactor.redact(text);
    return safe.length() <= size ? safe : safe.substring(0, size);
  }
}
