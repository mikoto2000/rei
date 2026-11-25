package dev.mikoto2000.rei.llm;

import java.util.Map;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Native request generation rebuilt from a credential-free accepted snapshot. */
final class RunNativeOptions {
  private RunNativeOptions() {}

  static Map<String, Object> generation(ToolCallingChatOptions options) { return Map.of(); }

  static ToolCallingChatOptions options(RunLlmConnection connection) {
    return OpenAiChatOptions.builder().build();
  }
}
