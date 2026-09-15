package dev.mikoto2000.rei.externalagent;

import org.springframework.stereotype.Component;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.*;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

@Component
public class ExternalAgentTools {
  private final ExternalAgentDelegationService service;
  public ExternalAgentTools(ExternalAgentDelegationService service) { this.service = service; }
  @Tool(description = "Workflow: Request a read-only Codex review ONLY when the current user explicitly requests Codex. Once per run. Supply a concise review task and relevant design decisions, never full history or source files. Independently evaluate findings before answering; do not automatically fix anything. External failure does not prevent your own evaluation.")
  public ExternalAgentResult requestCodexReview(String task, @ToolParam(required = false) String target,
      @ToolParam(required = false) String context, ToolContext toolContext) {
    var run = toolContext == null ? null : (RunExecutionContext) toolContext.getContext().get(RunExecutionContext.KEY);
    return service.review(run, task, target, context);
  }
}
