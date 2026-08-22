package dev.mikoto2000.rei.ui.projection;

import java.util.List;

public record AgentUiState(
    AgentRunView run,
    List<MessageView> messages,
    List<ToolExecutionView> tools,
    long lastSequence) {

  public AgentUiState {
    messages = List.copyOf(messages);
    tools = List.copyOf(tools);
  }
}
