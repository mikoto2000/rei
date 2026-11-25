package dev.mikoto2000.rei.llm;

import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/** Resolves the chat model for display using the same settings as request routing. */
@Component
public final class ChatModelResolver implements UnaryOperator<String> {
  private final UnaryOperator<String> resolveModel;

  public ChatModelResolver(LlmModelProvider models) {
    this.resolveModel = selected -> models.model(LlmFeature.CHAT, selected);
  }

  @Override
  public String apply(String selectedModel) {
    return resolveModel.apply(selectedModel);
  }
}
