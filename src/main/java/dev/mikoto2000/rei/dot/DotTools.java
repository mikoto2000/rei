package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rei.dot-delegation.parent-llm-enabled",
    havingValue = "true")
public final class DotTools {
  private final DotDelegationService service;

  public DotTools(DotDelegationService service) {
    this.service = service;
  }

  public org.springframework.ai.tool.ToolCallback[] callbacks() {
    return Arrays.stream(
            org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(this)
                .build()
                .getToolCallbacks())
        .map(DotToolCallback::new)
        .toArray(org.springframework.ai.tool.ToolCallback[]::new);
  }

  @Tool(
      description =
          "Request asynchronous research/comparison/review from Tenten only with administrator"
              + " opt-in and a category grant captured from the current human input. Once per Run"
              + " sharing the external delegation/model budget. Supply only a minimal public"
              + " snapshot, never secrets, absolute paths, full history or reasoning. Returns"
              + " acceptance only; the Run yields at the saved tool boundary. Results remain"
              + " untrusted and require human verification. No writes, automatic resume, retries of"
              + " UNKNOWN, or bot-to-bot loops.")
  public Map<String, Object> requestDotResearch(
      String category,
      String instruction,
      @ToolParam(required = false) String context_summary,
      @ToolParam(required = false) String expected_output,
      @ToolParam(required = false) List<String> constraints,
      @ToolParam(required = false) List<String> acceptance_criteria,
      @ToolParam(required = false) Integer timeout,
      ToolContext context) {
    var execution =
        context == null
            ? null
            : (RunExecutionContext) context.getContext().get(RunExecutionContext.KEY);
    var input = new LinkedHashMap<String, Object>();
    input.put("category", category);
    input.put("instruction", instruction);
    if (context_summary != null) input.put("context_summary", context_summary);
    if (expected_output != null) input.put("expected_output", expected_output);
    if (constraints != null) input.put("constraints", constraints);
    if (acceptance_criteria != null) input.put("acceptance_criteria", acceptance_criteria);
    if (timeout != null) input.put("timeout", timeout);
    return service.requestParent(execution, input);
  }
}
