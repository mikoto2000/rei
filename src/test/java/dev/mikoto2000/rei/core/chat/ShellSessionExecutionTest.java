package dev.mikoto2000.rei.core.chat;

import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.working.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.ui.shell.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShellSessionExecutionTest {
  @TempDir Path directory;
  @Test void switchUsesPersistedTurnsInNextRequestAfterRestart() {
    var projects = new ProjectService(directory, new ProjectRegistry(directory.resolve("projects.json")));
    var repository = new FileSessionRepository(directory.resolve("sessions.json"));
    var lifecycle = new SessionLifecycle(repository, Clock.systemUTC());
    var saved = lifecycle.submit(projects.currentContext(), null, "saved-user-marker",
        AgentRunContext.RequestSource.WEB, c -> {});
    var disk = new ConversationTurnStore(directory);
    disk.startOrdered(saved, "saved-user-marker", java.time.Instant.now());
    disk.finish(saved, ConversationTurnStore.Status.COMPLETED, "saved-answer-marker");
    var restored = new ConversationTurnStore(directory);
    var memory = MessageWindowChatMemory.builder().build();
    List<Prompt> prompts = new ArrayList<>();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
      }
    };
    var advisor = new dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor(restored, memory,
        new dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository(directory));
    var client = ChatClient.builder(model).defaultAdvisors(RunScopedAdvisor.wrap(List.of(advisor))).build();
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var execution = new ChatExecutionService(client, holder, new CommandCancellationService(), Optional.empty());
    execution.setConversationTurnStore(restored); execution.setChatMemory(memory);
    var restarted = new ShellConversationService(projects,
        new SessionLifecycle(new FileSessionRepository(directory.resolve("sessions.json")), Clock.systemUTC()),
        (context, prompt) -> assertThat(execution.execute(context, prompt, new UserInterventionQueue()).success()).isTrue());
    try (var scope = projects.newClient().open()) {
      assertThat(memory.get(saved.conversationId())).isEmpty();
      assertThat(new picocli.CommandLine(new ResumeConversationCommand(restarted)).execute(saved.conversationId())).isZero();
      restarted.submit("continue-after-restart");
      assertThat(prompts.getLast().toString()).contains("saved-user-marker", "saved-answer-marker", "continue-after-restart");
      restarted.newConversation();
      restarted.submit("fresh-after-restart");
      assertThat(prompts.getLast().toString()).contains("fresh-after-restart").doesNotContain("saved-user-marker", "saved-answer-marker");
    }
  }
  @Test void commandsContinueSelectedHistoryAndWorkingSetWithoutLeakingIntoNewSession() {
    var projects = new ProjectService(directory, new ProjectRegistry(directory.resolve("projects.json")));
    var repository = new FileSessionRepository(directory.resolve("sessions.json"));
    var memory = MessageWindowChatMemory.builder().build();
    List<Prompt> prompts = new ArrayList<>();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
      }
    };
    var client = ChatClient.builder(model).defaultAdvisors(RunScopedAdvisor.wrap(List.of(
        org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor.builder(memory).build()))).build();
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var execution = new ChatExecutionService(client, holder, new CommandCancellationService(), Optional.empty());
    execution.setChatMemory(memory);
    var shell = new ShellConversationService(projects, new SessionLifecycle(repository, Clock.systemUTC()),
        (context, prompt) -> assertThat(execution.execute(context, prompt, new UserInterventionQueue()).success()).isTrue());
    var previous = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", directory.toString());
    try (var application = new AnnotationConfigApplicationContext(); var scope = projects.newClient().open()) {
      application.registerBean(Clock.class, Clock::systemUTC);
      application.registerBean(AgentEventFactory.class, () -> new AgentEventFactory(Clock.systemUTC()));
      application.registerBean(AgentEventBus.class, InMemoryAgentEventBus::new);
      application.register(ProjectScopeConfiguration.class, WorkingSetConfiguration.class);
      application.refresh();
      var working = application.getBean(WorkingSet.class);
      var first = shell.submit("alpha-marker");
      working.recordRead(directory.resolve("alpha.txt"));
      assertThat(new picocli.CommandLine(new NewConversationCommand(shell)).execute()).isZero();
      var secondId = shell.currentSessionId();
      assertThat(working.getFiles()).isEmpty();
      shell.submit("beta-marker");
      assertThat(prompts.getLast().toString()).contains("beta-marker").doesNotContain("alpha-marker");
      working.recordRead(directory.resolve("beta.txt"));
      assertThat(new picocli.CommandLine(new ResumeConversationCommand(shell)).execute(first.conversationId())).isZero();
      assertThat(working.getFiles()).extracting(FileReference::path).containsExactly(directory.resolve("alpha.txt").toString());
      shell.submit("continue-alpha");
      assertThat(prompts.getLast().toString()).contains("alpha-marker", "continue-alpha").doesNotContain("beta-marker");
      assertThat(memory.get(secondId).toString()).contains("beta-marker").doesNotContain("alpha-marker");
      assertThat(projects.currentContext().id()).isEqualTo(first.projectId());
    } finally {
      if (previous == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", previous);
    }
  }
}
