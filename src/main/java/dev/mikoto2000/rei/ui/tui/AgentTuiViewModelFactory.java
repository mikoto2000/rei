package dev.mikoto2000.rei.ui.tui;

import java.util.List;

import dev.mikoto2000.rei.ui.projection.AgentRunStatus;
import dev.mikoto2000.rei.ui.projection.AgentUiState;
import dev.mikoto2000.rei.ui.projection.MessageView;
import dev.mikoto2000.rei.ui.projection.ToolExecutionStatus;
import dev.mikoto2000.rei.ui.projection.ToolExecutionView;

final class AgentTuiViewModelFactory {

  AgentTuiRenderModel create(AgentUiState state, AgentTuiInput input, boolean busy, int maxTools) {
    List<ToolExecutionView> tools = state.tools();
    int from = Math.max(0, tools.size() - Math.max(0, maxTools));
    List<String> toolLines = tools.subList(from, tools.size()).stream().map(this::toolLine).toList();
    return new AgentTuiRenderModel(
        state.run().status().name(),
        latestAssistantText(state),
        toolLines,
        input.text(),
        input.textBeforeCursor(),
        busy || state.run().status() == AgentRunStatus.RUNNING);
  }

  private String latestAssistantText(AgentUiState state) {
    List<MessageView> messages = state.messages();
    for (int index = messages.size() - 1; index >= 0; index--) {
      MessageView message = messages.get(index);
      if (message.role() == null || "assistant".equalsIgnoreCase(message.role())) {
        return message.text();
      }
    }
    return "";
  }

  private String toolLine(ToolExecutionView tool) {
    String symbol = switch (tool.status()) {
      case RUNNING -> "→";
      case COMPLETED -> "✓";
      case FAILED -> "✗";
    };
    StringBuilder line = new StringBuilder(symbol).append(' ').append(tool.toolName());
    if (tool.status() == ToolExecutionStatus.COMPLETED && tool.durationMillis() != null) {
      line.append("  ").append(tool.durationMillis()).append(" ms");
    }
    if (tool.status() == ToolExecutionStatus.FAILED && tool.error() != null
        && tool.error().message() != null && !tool.error().message().isBlank()) {
      line.append("  ").append(tool.error().message());
    }
    return line.toString();
  }
}
