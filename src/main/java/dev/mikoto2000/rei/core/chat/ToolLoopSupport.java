package dev.mikoto2000.rei.core.chat;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;

/** Shared Spring AI tool dispatch boundary for explicit application-owned loops. */
public final class ToolLoopSupport {
  /** Refuse ambient tool authority; isolated requests must explicitly supply their callbacks. */
  public static void requireNoDefaultTools(org.springframework.ai.chat.model.ChatModel model) {
    var options = model.getOptions();
    requireNoRawTools(options);
    if (options instanceof ToolCallingChatOptions tools
        && !org.springframework.util.CollectionUtils.isEmpty(tools.getToolCallbacks())) {
      throw new IllegalArgumentException("SubAgent model must not carry default tools");
    }
  }

  /** SDK extra-body parameters must not bypass Rei's explicit callback/permission boundary. */
  public static void requireNoRawTools(ChatOptions options) {
    if (options instanceof org.springframework.ai.openai.OpenAiChatOptions openAi
        && openAi.getExtraBody() != null) {
      Object rawTools = openAi.getExtraBody().get("tools");
      if (rawTools != null && !(rawTools instanceof java.util.Collection<?> tools && tools.isEmpty())) {
        throw new IllegalArgumentException("Model must not carry raw default tools");
      }
    }
  }

  // Rei already bounds explicit loops with durable run budgets/maxSteps and timeouts.
  // Library history-based limits reset at user interventions and can abort a tool batch
  // before Rei checkpoints it, so retain the existing application-owned limits.
  private final ToolCallingManager manager = ToolCallingManager.builder()
      .unlimitedCallsPerTool().unlimitedTotalToolCalls().build();
  public ToolExecutionResult execute(Prompt prompt, ChatResponse response) {
    return manager.executeToolCalls(prompt, response);
  }
}
