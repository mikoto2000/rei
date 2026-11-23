package dev.mikoto2000.rei.llm.discovery;

import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import java.util.HashSet;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Validates the actual outbound options, not an earlier candidate list. */
public final class ToolRequestValidator {
  private ToolRequestValidator() {}

  public static void validate(Prompt prompt, int maximum) {
    ToolLoopSupport.requireNoRawTools(prompt.getOptions());
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) return;
    var callbacks = options.getToolCallbacks();
    if (callbacks == null) return;
    if (callbacks.size() > maximum)
      throw new IllegalStateException(
          "Tool count " + callbacks.size() + " exceeds endpoint limit " + maximum);
    var names = new HashSet<String>();
    callbacks.forEach(
        c -> {
          if (!names.add(c.getToolDefinition().name()))
            throw new IllegalStateException(
                "Duplicate outbound tool: " + c.getToolDefinition().name());
        });
  }
}
