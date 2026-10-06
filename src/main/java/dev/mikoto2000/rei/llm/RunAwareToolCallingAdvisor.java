package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import reactor.core.publisher.Flux;

/** Retains legacy tool calling while allowing Rei's run loop to own budgets and permissions. */
public final class RunAwareToolCallingAdvisor extends ToolCallingAdvisor {
  public RunAwareToolCallingAdvisor() {
    // The old model-owned loop ran beneath prompt and memory advisors; preserve that boundary.
    super(ToolCallingManager.builder().build(), DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER,
        org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1, true);
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    return applicationOwnsTools(request) ? chain.nextCall(request) : super.adviseCall(request, chain);
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
    return applicationOwnsTools(request) ? chain.nextStream(request) : super.adviseStream(request, chain);
  }

  private boolean applicationOwnsTools(ChatClientRequest request) {
    return request.prompt().getOptions() instanceof ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(RunExecutionContext.KEY) instanceof RunExecutionContext;
  }
}
