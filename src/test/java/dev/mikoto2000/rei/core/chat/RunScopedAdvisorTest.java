package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;

class RunScopedAdvisorTest {
  @Test void nestedClientCapturesOwnerBeforeAdvisorScheduler() {
    var run = new AgentRunContext("run", "chat:main", Path.of("a"));
    BaseAdvisor delegate = new BaseAdvisor() {
      public String getName() { return "test-owner"; }
      public int getOrder() { return 1; }
      public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        assertThat(AgentRunScope.current()).isEqualTo(run); return request;
      }
      public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return response; }
    };
    var model = new org.springframework.ai.chat.model.ChatModel() {
      public org.springframework.ai.chat.model.ChatResponse call(Prompt p) {
        return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
            new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("ok"))));
      }
      public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt p) {
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) p.getOptions()).getToolContext())
            .containsEntry(AgentRunContext.class.getName(), run);
        return reactor.core.publisher.Flux.just(call(p));
      }
    };
    var client = ChatClient.builder(model).defaultOptions(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build())
        .defaultAdvisors(new RunScopedAdvisor(delegate)).build();
    try (var scope = AgentRunScope.open(run)) { assertThat(client.prompt("nested").stream().content().blockLast()).isEqualTo("ok"); }
  }

  @Test void advisorReentersCapturedContextAndRestoresThread() {
    var run = new AgentRunContext("run", "chat:main", Path.of("a"));
    BaseAdvisor delegate = new BaseAdvisor() {
      public String getName() { return "test-owner"; }
      public int getOrder() { return 1; }
      public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        assertThat(AgentRunScope.current()).isEqualTo(run); return request;
      }
      public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return response; }
    };
    var request = ChatClientRequest.builder().prompt(new Prompt("work"))
        .context(Map.of(AgentRunContext.class.getName(), run)).build();
    new RunScopedAdvisor(delegate).before(request, null);
    assertThat(AgentRunScope.current()).isNull();
  }
}
