package dev.mikoto2000.rei.core.completion;

import java.util.List;
import java.util.Set;

/** Ordinary prose is not a path request. Explicit path prefixes opt into fallback. */
public final class PathFallbackCompletionProvider implements CompletionProvider {
  private final FilePathCompletionProvider paths = new FilePathCompletionProvider();
  @Override public int priority() { return 0; }
  @Override public boolean supports(CompletionContext context) {
    String word = context.currentToken();
    return context.types().isEmpty() && (word.startsWith("./") || word.startsWith("../")
        || word.startsWith("~/") || word.startsWith("/") || word.startsWith(".\\")
        || word.startsWith("..\\") || word.startsWith("~\\") || word.matches("^[a-zA-Z]:[\\\\/].*")
        || word.startsWith("\\\\"));
  }
  @Override public List<CompletionCandidate> complete(CompletionContext context) {
    return paths.complete(context.withTypes(Set.of(FilePathCompletionProvider.BOTH)));
  }
}
