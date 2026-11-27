package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modeloptions.ChatModelSelection;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves the chat model for display using the same settings as request routing. */
@Component
public final class ChatModelResolver implements UnaryOperator<String> {
  private final UnaryOperator<String> resolveModel;
  private java.util.function.Supplier<ChatModelSelection> chatSelection = () -> null;

  @Autowired
  void setChatSelection(ObjectProvider<ChatModelSelection> selection) {
    chatSelection = selection::getIfAvailable;
  }

  public ChatModelResolver(LlmModelProvider models) {
    this.resolveModel = selected -> models.model(LlmFeature.CHAT, selected);
  }

  @Override
  public String apply(String selectedModel) {
    var selection = chatSelection.get();
    if (selection != null) return selection.selection().model();
    return resolveModel.apply(selectedModel);
  }
}
