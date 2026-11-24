package dev.mikoto2000.rei.modeloptions;

import java.util.List;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Native request option transformations; no execution, storage or connection resolution. */
public final class ModelOptions {
  private ModelOptions() {}

  /** Remove explicit callbacks and use the native tool-less request shape. */
  public static ToolCallingChatOptions withoutTools(ToolCallingChatOptions options) {
    if (options instanceof OpenAiResponsesChatOptions responses)
      return responses.mutate().toolCallbacks(List.of()).toolChoice(null).build();
    if (options instanceof OpenAiChatOptions chat)
      return chat.mutate().toolCallbacks(List.of()).toolChoice("none").build();
    return options.mutate().toolCallbacks(List.of()).build();
  }
}
