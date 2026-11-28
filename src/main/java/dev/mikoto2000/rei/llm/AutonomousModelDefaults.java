package dev.mikoto2000.rei.llm;

import java.util.Collection;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Reject ambient request content before constructing a public-only inference path. */
final class AutonomousModelDefaults {
  private AutonomousModelDefaults() {}

  static void requirePublicOnly(ChatModel model) {
    if (!(model.getOptions() instanceof OpenAiChatOptions options))
      throw new IllegalArgumentException("Unsupported autonomous model defaults");
    if (options.getUser() != null
        || present(options.getMetadata())
        || options.getPromptCacheKey() != null
        || present(options.getExtraBody())
        || present(options.getStopSequences())
        || options.getOutputSchema() != null
        || options.getResponseFormat() != null
        || present(options.getToolContext())
        || present(options.getToolCallbacks())
        || present(options.getOutputModalities())
        || options.getOutputAudio() != null
        || Boolean.TRUE.equals(options.getReplayReasoningContent()))
      throw new IllegalArgumentException("Autonomous model must not inherit request content");
  }

  private static boolean present(Map<?, ?> values) {
    return values != null && !values.isEmpty();
  }

  private static boolean present(Collection<?> values) {
    return values != null && !values.isEmpty();
  }
}
