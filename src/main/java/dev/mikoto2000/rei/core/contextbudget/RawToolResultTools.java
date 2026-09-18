package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.ai.tool.annotation.Tool;
import dev.mikoto2000.rei.core.chat.AgentRunScope;

public class RawToolResultTools {
  private final RawToolResultStore store;
  public RawToolResultTools(RawToolResultStore store) { this.store = store; }
  @Tool(description = "Read a page of a saved raw tool result using rawResultRef from a compact result. Offset is a character offset, limit 1..16000.")
  public String readRawToolResult(String rawResultRef, int offset, int limit) {
    var owner = AgentRunScope.current();
    if (owner == null) throw new IllegalStateException("A run is required");
    if (offset < 0 || limit < 1 || limit > 16000) throw new IllegalArgumentException("Invalid result page");
    var result = store.read(owner.conversationId(), rawResultRef);
    String raw = result.rawResult();
    if (offset > raw.length()) throw new IllegalArgumentException("Offset beyond result");
    return "toolName: " + result.toolName() + "\ntoolCallId: " + result.toolCallId()
        + "\noffset: " + offset + "\ntotalCharacters: " + raw.length() + "\n"
        + raw.substring(offset, (int) Math.min(raw.length(), (long) offset + limit));
  }
}
