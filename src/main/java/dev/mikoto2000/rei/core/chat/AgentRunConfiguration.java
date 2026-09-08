package dev.mikoto2000.rei.core.chat;

import java.util.concurrent.*;
import org.springframework.context.annotation.*;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;

@Configuration
public class AgentRunConfiguration {
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService agentRunExecutor() {
    return Executors.newSingleThreadExecutor(Thread.ofVirtual().name("rei-agent-", 0).factory());
  }

  @Bean
  public ConversationInputRouter conversationInputRouter(ExecutorService agentRunExecutor,
      ChatExecutionService execution, ChatResponseNarrator narrator,
      dev.mikoto2000.rei.event.AgentEventFactory events, dev.mikoto2000.rei.event.AgentEventPublisher publisher) {
    return new ConversationInputRouter(agentRunExecutor, (context, prompt, queue) -> {
      narrator.reset();
      try {
        var result = execution.execute(context, prompt, queue);
        if (result.success()) narrator.narrateIfCompleted(result.text());
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(AgentRunConfiguration.class).error("Agent run failed: {}", context.runId(), error);
      }
    }, (context, entry) -> publisher.publish(events.intervention(context.runId(), entry.id(), entry.text(), false)));
  }
}
