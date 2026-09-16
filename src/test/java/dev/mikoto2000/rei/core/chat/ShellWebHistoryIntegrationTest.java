package dev.mikoto2000.rei.core.chat;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.web.SessionController;
import dev.mikoto2000.rei.web.ChatController;
import dev.mikoto2000.rei.web.ApiExceptionHandler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShellWebHistoryIntegrationTest {
  @TempDir Path directory;
  @Test void shellWebShellShareHttpHistoryExecutionOrderAndModelMemory() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var service = new ProjectService(directory, projects);
    var repo = new FileSessionRepository(directory.resolve("sessions.json"));
    var turns = new ConversationTurnStore(directory);
    var memory = MessageWindowChatMemory.builder().build();
    List<Prompt> modelInputs = new ArrayList<>();
    var model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        modelInputs.add(p);
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer " + modelInputs.size())))));
      }
    };
    var client = ChatClient.builder(model).defaultAdvisors(RunScopedAdvisor.wrap(List.of(
        org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor.builder(memory).build()))).build();
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);
    var events = new InMemoryAgentEventBus(); var factory = new AgentEventFactory(clock);
    var cancellation = new CommandCancellationService();
    var execution = new ChatExecutionService(client, holder, cancellation, Optional.empty(), factory, events);
    execution.setChatMemory(memory); execution.setConversationTurnStore(turns);
    var tasks = new ArrayList<Runnable>(); var executed = new ArrayList<AgentRunContext>();
    var router = new ConversationInputRouter(tasks::add, (c,p,q) -> { executed.add(c); execution.execute(c,p,q); });
    var lifecycle = new SessionLifecycle(repo, clock);
    var shell = new ShellConversationService(service, lifecycle, router::submit);
    var registry = new RunRegistry(clock);
    try (var runs = new RunService(registry, events, factory, cancellation, router::cancelQueued);
        var scope = service.newClient().open()) {
      var web = new ChatSubmitService(projects, new SessionRegistry(clock), registry, lifecycle,
          (c,p) -> router.submit(c,p,work -> runs.execute(c,work)));
      var mvc = MockMvcBuilders.standaloneSetup(new SessionController(new SessionQueryService(repo, turns)), new ChatController(web))
          .setControllerAdvice(new ApiExceptionHandler()).build();
      var first = shell.submit("shell-one");
      mvc.perform(get("/api/v1/sessions")).andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].sessionId").value(first.conversationId()));
      mvc.perform(get("/api/v1/sessions/{id}",first.conversationId())).andExpect(status().isOk())
          .andExpect(jsonPath("$.projectId").value(project.id()));
      tasks.removeFirst().run();
      mvc.perform(get("/api/v1/sessions/{id}/turns",first.conversationId())).andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].turnId").value(first.runId()))
          .andExpect(jsonPath("$.items[0].userMessage").value("shell-one"))
          .andExpect(jsonPath("$.items[0].assistantMessage").value("answer 1"));
      mvc.perform(post("/api/v1/chat").contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
          Map.of("message","web-two","projectId",project.id(),"sessionId",first.conversationId()))))
          .andExpect(status().isAccepted()).andExpect(jsonPath("$.sessionId").value(first.conversationId()));
      var third = shell.submit("shell-three");
      assertThat(tasks).hasSize(1);
      while (!tasks.isEmpty()) tasks.removeFirst().run();
      assertThat(executed).extracting(AgentRunContext::conversationId).containsOnly(first.conversationId());
      assertThat(executed).extracting(AgentRunContext::requestSource).containsExactly(AgentRunContext.RequestSource.SHELL, AgentRunContext.RequestSource.WEB, AgentRunContext.RequestSource.SHELL);
      assertThat(modelInputs.getLast().toString()).contains("shell-one","web-two","shell-three");
      mvc.perform(get("/api/v1/sessions/{id}/turns",first.conversationId())).andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(3))
          .andExpect(jsonPath("$.items[0].userMessage").value("shell-one"))
          .andExpect(jsonPath("$.items[1].userMessage").value("web-two"))
          .andExpect(jsonPath("$.items[2].runId").value(third.runId()));
      assertThat(new ConversationTurnStore(directory).read(first.conversationId())).hasSize(3);
      var fromWeb = web.submit("web-origin", project.id(), null);
      shell.resume(fromWeb.conversationId());
      assertThat(shell.submit("shell continuation").conversationId()).isEqualTo(fromWeb.conversationId());
      while (!tasks.isEmpty()) tasks.removeFirst().run();
      assertThat(turns.read(fromWeb.conversationId())).hasSize(2);
    }
  }
}
