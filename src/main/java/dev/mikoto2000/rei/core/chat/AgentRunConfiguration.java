package dev.mikoto2000.rei.core.chat;

import java.util.concurrent.*;
import org.springframework.context.annotation.*;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;

@Configuration
public class AgentRunConfiguration {
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService agentRunExecutor() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rei-agent-", 0).factory());
  }

  @Bean
  public ConversationInputRouter conversationInputRouter(ExecutorService agentRunExecutor,
      ChatExecutionService execution, ChatResponseNarrator narrator,
      dev.mikoto2000.rei.event.AgentEventFactory events, dev.mikoto2000.rei.event.AgentEventPublisher publisher) {
    return new ConversationInputRouter(agentRunExecutor, (context, prompt, queue) -> {
      try {
        var result = execution.execute(context, prompt, queue);
        if (result.success()) {
          // Audio can block for minutes; it is not an active AgentRun and must not hold its mailbox open.
          try {
            agentRunExecutor.execute(() -> {
              try (var scope = AgentRunScope.open(context)) {
                narrator.narrateCompletedRun(result.text());
              }
            });
          }
          catch (RejectedExecutionException closing) {
            org.slf4j.LoggerFactory.getLogger(AgentRunConfiguration.class).debug("Narration skipped during shutdown");
          }
        }
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(AgentRunConfiguration.class).error("Agent run failed: {}", context.runId(), error);
        publisher.publish(events.runFailed(context.runId(), dev.mikoto2000.rei.event.ErrorInformation.from(error)).withOwnership(context));
      }
    }, (context, entry) -> publisher.publish(events.intervention(context.runId(), entry.id(), entry.text(), false).withOwnership(context)));
  }
}
