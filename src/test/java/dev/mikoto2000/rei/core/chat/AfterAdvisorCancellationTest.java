package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

class AfterAdvisorCancellationTest {
  @Test
  void responseSideAdvisorIsOwnedUntilItsActualCallbackReturns() {
    var cancellation = new CommandCancellationService();
    var owner = new AgentRunContext("after", "session", Path.of("."), "project");
    cancellation.prepare(owner);
    var tracked = new AtomicBoolean();
    var advisor =
        new RunScopedAdvisor(
            new BaseAdvisor() {
              public int getOrder() {
                return 0;
              }

              public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
                return request;
              }

              public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
                tracked.set(cancellation.hasActiveChildren(owner.runId()));
                return response;
              }
            });
    advisor.after(
        ChatClientResponse.builder()
            .context(
                Map.of(
                    AgentRunContext.class.getName(), owner,
                    CommandCancellationService.class.getName(), cancellation))
            .build(),
        null);
    assertThat(tracked).isTrue();
    assertThat(cancellation.hasActiveChildren(owner.runId())).isFalse();
    cancellation.finish(owner.runId());
  }
}
