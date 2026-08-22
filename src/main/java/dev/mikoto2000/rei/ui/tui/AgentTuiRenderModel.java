package dev.mikoto2000.rei.ui.tui;

import java.util.List;

record AgentTuiRenderModel(
    String status,
    String assistantText,
    List<String> toolLines,
    String input,
    String inputBeforeCursor,
    boolean agentRunning) {

  AgentTuiRenderModel {
    toolLines = List.copyOf(toolLines);
  }
}
