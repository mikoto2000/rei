package dev.mikoto2000.rei.core.chat;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;

/** Shared Spring AI tool dispatch boundary for explicit application-owned loops. */
public final class ToolLoopSupport {
  /** Spring AI merges empty runtime tool lists with model defaults. Refuse that ambient authority. */
  public static void requireNoDefaultTools(org.springframework.ai.chat.model.ChatModel model) {
    if (model.getDefaultOptions() instanceof ToolCallingChatOptions options
        && (!org.springframework.util.CollectionUtils.isEmpty(options.getToolCallbacks())
            || !org.springframework.util.CollectionUtils.isEmpty(options.getToolNames()))) {
      throw new IllegalArgumentException("SubAgent model must not carry default tools");
    }
  }
  private final ToolCallingManager manager = ToolCallingManager.builder().build();
  public ToolExecutionResult execute(Prompt prompt, ChatResponse response) {
    return manager.executeToolCalls(prompt, response);
  }
}
