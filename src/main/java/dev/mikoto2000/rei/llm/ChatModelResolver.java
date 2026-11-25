package dev.mikoto2000.rei.llm;

import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/** Resolves the chat model for display using the same settings as request routing. */
@Component
public final class ChatModelResolver implements UnaryOperator<String> {
  private final LlmModelProvider models;

  public ChatModelResolver(LlmModelProvider models) {
    this.models = models;
  }

  @Override
  public String apply(String selectedModel) {
    return models.model(LlmFeature.CHAT, selectedModel);
  }
}
