package dev.mikoto2000.rei.llm;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil.ResolvedConnectionProperties;

/** Resolves the existing feature/global connection without exposing its credentials to a Run. */
final class LegacyChatConnectionSource {
  private final LlmModelProvider models;

  LegacyChatConnectionSource(LlmModelProvider models, LlmProperties properties,
      Supplier<ResolvedConnectionProperties> global, Supplier<String> environmentKey) {
    this.models = models;
  }

  RunLlmConnectionFactory.LegacySettings resolve(String model) {
    return new RunLlmConnectionFactory.LegacySettings(models.resolvedEndpoint(LlmFeature.CHAT), Map.of());
  }

  String credential(RunLlmConnection accepted) { return null; }
}
