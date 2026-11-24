package dev.mikoto2000.rei.modeloptions;

import java.util.List;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Native request option transformations; no execution, storage or connection resolution. */
public final class ModelOptions {
  public static final String ENDPOINT_CONTEXT_KEY = "rei.llm.resolved-endpoint";
  public static final String MAX_TOOLS_CONTEXT_KEY = "rei.llm.max-tools";

  private ModelOptions() {}

  /** Select explicitly published callbacks using the native automatic function-call shape. */
  public static ToolCallingChatOptions withTools(ToolCallingChatOptions options,
      List<org.springframework.ai.tool.ToolCallback> callbacks) {
    if (callbacks.isEmpty()) return withoutTools(options);
    if (options instanceof OpenAiResponsesChatOptions responses)
      return responses.mutate().toolCallbacks(callbacks).toolChoice("auto").build();
    if (options instanceof OpenAiChatOptions chat)
      return chat.mutate().toolCallbacks(callbacks).toolChoice("auto").build();
    return options.mutate().toolCallbacks(callbacks).build();
  }

  /** Remove explicit callbacks and use the native tool-less request shape. */
  public static ToolCallingChatOptions withoutTools(ToolCallingChatOptions options) {
    if (options instanceof OpenAiResponsesChatOptions responses) {
      var builder = responses.mutate();
      builder.toolCallbacks(List.of());
      builder.toolChoice(null);
      return builder.build();
    }
    if (options instanceof OpenAiChatOptions chat) {
      var builder = chat.mutate();
      builder.toolCallbacks(List.of());
      builder.toolChoice("none");
      return builder.build();
    }
    return options.mutate().toolCallbacks(List.of()).build();
  }
}
