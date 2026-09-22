package dev.mikoto2000.rei.core.completion;

import java.util.List;

public final class ChoiceCompletionProvider implements CompletionProvider {
  @Override public boolean supports(CompletionContext context) { return context.types().contains("choices"); }
  @Override public List<CompletionCandidate> complete(CompletionContext context) {
    return context.choices().stream().filter(c -> c.value().startsWith(context.currentToken())).toList();
  }
}
