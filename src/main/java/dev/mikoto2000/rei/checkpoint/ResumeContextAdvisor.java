package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.util.ArrayList;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Historical data is injected separately from the user's request and remains within
 * ContextAssembler's hard budget.
 */
@Component
public class ResumeContextAdvisor implements BaseAdvisor {
  private final PersistentCheckpointService service;

  public ResumeContextAdvisor(PersistentCheckpointService service) {
    this.service = service;
  }

  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    var run = AgentRunScope.current();
    if (run == null) return request;
    String context = service.context(run.runId());
    if (context.isBlank()) return request;
    var messages = new ArrayList<Message>(request.prompt().getInstructions());
    messages.addFirst(
        SystemMessage.builder()
            .text(context)
            .metadata(java.util.Map.of("rei.resumeContext", true))
            .build());
    return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
  }

  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  public int getOrder() {
    return -66;
  }
}
