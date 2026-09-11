package dev.mikoto2000.rei.computeruse;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;

public final class ComputerUseTools {
  private final ComputerUseService service;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory factory;
  private final AgentEventPublisher publisher;
  public ComputerUseTools(ComputerUseService service, CommandCancellationService cancellation,
      AgentEventFactory factory, AgentEventPublisher publisher) {
    this.service = service; this.cancellation = cancellation; this.factory = factory; this.publisher = publisher;
  }

  public String orchestrationPrompt() {
    try {
      return new org.springframework.core.io.ClassPathResource("computer-use/orchestration.md")
          .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Missing desktop orchestration instructions", error);
    }
  }

  @Tool(description = "Visual interaction with the Windows primary monitor. First use existing Shell/file/API tools for programmable preparation such as opening URLs or launching applications. Pass the remaining goal and completed preparation here; observe a fresh screenshot and perform one action at a time. Returns DONE only when visually verified; safety-blocked actions require human approval. Do not retry blocked or failed workflows blindly.")
  public ComputerUseResult computerUse(String goal) {
    var owner = AgentRunScope.current();
    Thread worker = Thread.currentThread();
    var registration = cancellation.onCancel(owner == null ? null : owner.runId(), worker::interrupt);
    try { return service.run(goal); } finally { registration.dispose(); }
  }

  public ToolCallback callback() {
    var delegate = MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks()[0];
    return new ToolEventCallbackDecorator(delegate, factory, publisher);
  }
}
