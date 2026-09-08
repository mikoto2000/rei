package dev.mikoto2000.rei.core.chat;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.event.*;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectRunSwitchIntegrationTest {
  @TempDir Path temp;
  @Test void toolRunAndInterventionFinishInAAfterShellSwitchesToB() throws Exception {
    String prior = System.getProperty("rei.data-dir");
    Path data = temp.resolve("data"); System.setProperty("rei.data-dir", data.toString());
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Path a = Files.createDirectory(temp.resolve("a")); Path b = Files.createDirectory(temp.resolve("b"));
      var projects = new ProjectService(a, new ProjectRegistry(data.resolve("projects.json")));
      var run = new AgentRunContext("run-a", projects.currentContext(), "chat:main");
      var bus = new InMemoryAgentEventBus();
      var events = new AgentEventFactory(Clock.systemUTC());
      var store = new ProjectAgentEventStore(data); bus.subscribe(store);
      var calls = new AtomicInteger();
      ChatModel model = new ChatModel() {
        public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
        public Flux<ChatResponse> stream(Prompt p) {
          if (calls.incrementAndGet() == 1) return Flux.just(new ChatResponse(List.of(new Generation(
              AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("tool", "function", "readFile", "{}"))).build()))));
          assertThat(p.getInstructions().stream().filter(UserMessage.class::isInstance).map(Message::getText))
              .contains("keep README");
          return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done in A")))));
        }
      };
      var tool = new ToolCallback() {
        public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("readFile").description("read").inputSchema("{}").build(); }
        public String call(String input) {
          entered.countDown();
          try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test tool release timed out"); }
          catch (InterruptedException e) { throw new IllegalStateException(e); }
          assertThat(AgentRunScope.current().projectRoot()).isEqualTo(a); return "read in A";
        }
        public String call(String input, ToolContext context) { return call(input); }
      };
      var memory = MessageWindowChatMemory.builder().maxMessages(100).build();
      var client = ChatClient.builder(new StagnationChatModel(model))
          .defaultToolCallbacks(new ToolEventCallbackDecorator(tool, events, bus))
          .defaultAdvisors(PromptChatMemoryAdvisor.builder(memory).build()).build();
      var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
      var service = new ChatExecutionService(client, holder, new CommandCancellationService(), Optional.empty(), events, bus);
      service.setChatMemory(memory); service.setConversationLogStore(new ConversationLogStore());
      var queue = new UserInterventionQueue(entry -> bus.publish(events.intervention(run.runId(), entry.id(), entry.text(), false).withOwnership(run)));
      var future = executor.submit(() -> service.execute(run, "fix", queue));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        queue.offer("keep README"); projects.cd(b.toString()); release.countDown();
        assertThat(future.get(10, TimeUnit.SECONDS).success()).isTrue();
        assertThat(new ConversationLogStore().readAll()).isEmpty();
        assertThat(store.recent(projects.currentContext().id(), 100)).isEmpty();
        assertThat(store.recent(run.projectId(), 100)).allMatch(event -> run.projectId().equals(event.projectId()))
            .anyMatch(event -> event.type() == AgentEventType.USER_INTERVENTION_APPLIED)
            .anyMatch(event -> event.type() == AgentEventType.AGENT_RUN_COMPLETED);
        projects.cd(a.toString());
        assertThat(new ConversationLogStore().readAll()).extracting(ConversationLogEntry::content)
            .contains("keep README", "done in A");
        assertThat(memory.get(run.conversationId())).anyMatch(message -> message instanceof UserMessage && message.getText().equals("keep README"));
      } finally { release.countDown(); }
    } finally {
      if (prior == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", prior);
    }
  }
}
