package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.MessageCompletedPayload;
import dev.mikoto2000.rei.event.MessageDeltaPayload;
import dev.mikoto2000.rei.event.MessageStartedPayload;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;

/** Append-only Shell presentation adapter for Agent events. */
public final class ShellAgentEventRenderer implements AgentEventListener {
  private final ShellEventOutput output;
  private String assistantMessageId;
  private boolean assistantLineOpen;
  private boolean toolInterruptedMessage;

  public ShellAgentEventRenderer(ShellEventOutput output) {
    this.output = output;
  }

  @Override
  public synchronized void onEvent(AgentEvent event) {
    switch (event.type()) {
      case AGENT_RUN_STARTED -> {
        closeAssistantLine();
        assistantMessageId = null;
        toolInterruptedMessage = false;
        output.println("[agent] running");
      }
      case AGENT_RUN_COMPLETED -> {
        closeAssistantLine();
        AgentRunCompletedPayload payload = (AgentRunCompletedPayload) event.payload();
        String tokens = payload.completionTokens() == null
            ? "tokens unavailable"
            : payload.completionTokens() + " tokens";
        output.println("[agent] completed (" + formatSeconds(payload.duration()) + " s, " + tokens + ")");
      }
      case AGENT_RUN_FAILED -> {
        closeAssistantLine();
        AgentRunFailedPayload payload = (AgentRunFailedPayload) event.payload();
        output.println("[agent] failed: " + errorMessage(payload.error()));
      }
      case MESSAGE_STARTED -> messageStarted((MessageStartedPayload) event.payload());
      case MESSAGE_DELTA -> messageDelta((MessageDeltaPayload) event.payload());
      case MESSAGE_COMPLETED -> messageCompleted((MessageCompletedPayload) event.payload());
      case TOOL_STARTED -> {
        beforeTool();
        ToolStartedPayload payload = (ToolStartedPayload) event.payload();
        output.println("  → " + payload.toolName());
      }
      case TOOL_COMPLETED -> {
        beforeTool();
        ToolCompletedPayload payload = (ToolCompletedPayload) event.payload();
        output.println("  ✓ " + payload.toolName() + " (" + payload.duration() + " ms)");
      }
      case TOOL_FAILED -> {
        beforeTool();
        ToolFailedPayload payload = (ToolFailedPayload) event.payload();
        output.println("  ✗ " + payload.toolName() + ": " + errorMessage(payload.error()));
      }
      default -> { }
    }
    output.flush();
  }

  private void messageStarted(MessageStartedPayload payload) {
    if ("assistant".equalsIgnoreCase(payload.role())) assistantMessageId = payload.messageId();
  }

  private void messageDelta(MessageDeltaPayload payload) {
    if (!payload.messageId().equals(assistantMessageId) || payload.delta() == null) return;
    if (toolInterruptedMessage) {
      output.println("");
      toolInterruptedMessage = false;
    }
    output.print(payload.delta());
    assistantLineOpen = true;
  }

  private void messageCompleted(MessageCompletedPayload payload) {
    if (!payload.messageId().equals(assistantMessageId)) return;
    closeAssistantLine();
    assistantMessageId = null;
    toolInterruptedMessage = false;
  }

  private void beforeTool() {
    if (assistantMessageId != null) toolInterruptedMessage = true;
    closeAssistantLine();
  }

  private void closeAssistantLine() {
    if (assistantLineOpen) {
      output.println("");
      assistantLineOpen = false;
    }
  }

  private String errorMessage(dev.mikoto2000.rei.event.ErrorInformation error) {
    if (error == null || error.message() == null || error.message().isBlank()) return "unknown error";
    return error.message();
  }

  private String formatSeconds(long durationMillis) {
    return String.format(java.util.Locale.ROOT, "%.1f", durationMillis / 1_000.0d);
  }
}
