package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.ui.shell.*;

class ComputerUseIntegrationTest {
  @Test void existingRunCancellationInterruptsToolAndRemovesChildRegistration() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> { cancellation.cancel(); return new ComputerAction.TypeText("must not type", ComputerAction.Risk.LOW); },
        (a,s) -> fail(), a -> fail(), SafetyPolicy.lowRiskOnly(), cancellation::isCancellationRequested, p -> {}, 20, 5);
    var owner = new AgentRunContext("cancel-run", "chat", java.nio.file.Path.of("."));
    try (var scope = AgentRunScope.open(owner)) {
      cancellation.begin(null);
      try {
        var tools = new ComputerUseTools(service,cancellation,factory,e -> {});
        assertEquals(ComputerUseResult.Status.CANCELLED,tools.computerUse("goal").status());
        assertTrue(Thread.interrupted());
        cancellation.cancel();
        assertFalse(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); cancellation.clear(); }
    }
  }
  @Test void workflowCallbackCarriesRunOwnershipAndProjectsProgressWithoutImagesOrTypedText() {
    var events = new ArrayList<AgentEvent>();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> o.step() == 1 ? new ComputerAction.TypeText("sensitive typed text", ComputerAction.Risk.LOW) : new ComputerAction.Done("Visible"),
        (a,s) -> {}, a -> {}, SafetyPolicy.lowRiskOnly(), cancellation::isCancellationRequested,
        p -> events.add(factory.computerUseProgress(p)), 20, 5);
    var tools = new ComputerUseTools(service, cancellation, factory, events::add);
    var callback = tools.callback();
    assertEquals("computerUse",callback.getToolDefinition().name());
    var owner = new AgentRunContext("run", "chat", java.nio.file.Path.of("."));
    String result = callback.call("{\"goal\":\"Enter text\"}", new ToolContext(Map.of(AgentRunContext.class.getName(), owner)));
    assertTrue(result.contains("DONE"));
    assertTrue(events.stream().allMatch(e -> "run".equals(e.runId())));
    assertTrue(events.stream().anyMatch(e -> e.type() == AgentEventType.TOOL_STARTED));
    assertTrue(events.stream().anyMatch(e -> e.type() == AgentEventType.TOOL_COMPLETED));
    assertFalse(events.toString().contains("sensitive typed text"));
    assertFalse(events.toString().contains("base64"));
    var rendered = new StringBuilder();
    var renderer = new ShellAgentEventRenderer(new ShellEventOutput() {
      public void print(String text) { rendered.append(text); }
      public void println(String text) { rendered.append(text).append('\n'); }
      public void flush() {}
    });
    events.forEach(renderer::onEvent);
    assertTrue(rendered.toString().contains("[computer_use]"));
    assertTrue(rendered.toString().contains("DONE"));
  }

  @Test void oneDesktopWorkflowAtATimeIncludingRecursiveEntry() {
    var reference = new java.util.concurrent.atomic.AtomicReference<ComputerUseService>();
    var nested = new ArrayList<ComputerUseResult>();
    var service = new ComputerUseService(ComputerUseServiceTest::screen,
        o -> { nested.add(reference.get().run("nested")); return new ComputerAction.Done("Visible"); },
        (a,s) -> fail(), a -> {}, SafetyPolicy.lowRiskOnly(), () -> false, p -> {}, 20, 5);
    reference.set(service);
    assertEquals(ComputerUseResult.Status.DONE,service.run("goal").status());
    assertEquals(ComputerUseResult.Status.BUSY,nested.getFirst().status());
    assertEquals(ComputerUseResult.Status.DONE,service.run("again").status());
  }

  @Test void featureProviderRejectsToolsOnFallbackToo() {
    var model = mock(ChatModel.class);
    when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().toolNames("shell").build());
    var properties = new LlmProperties();
    var server = new LlmProperties.Server();
    server.setBaseUrl("https://example.invalid"); server.setModel("vision");
    properties.getFeatures().put("computer-use",server);
    assertThrows(IllegalArgumentException.class, () -> new LlmModelProvider(model,properties).computerUseChatModel());
  }
}
