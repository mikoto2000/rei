package dev.mikoto2000.rei.core.completion;

import java.util.*;

/** Fixed action choices for commands that parse their first positional argument themselves. */
public abstract class FirstArgumentCandidates implements CompletionMetadata {
  private final List<String> values;

  protected FirstArgumentCandidates(String... values) {
    this.values = List.of(values);
  }

  @Override
  public Set<String> types(CompletionContext context) {
    return context.argumentIndex() == 0 ? Set.of("choices") : Set.of();
  }

  @Override
  public List<CompletionCandidate> choices(CompletionContext context) {
    return context.argumentIndex() == 0 ? CompletionMetadata.super.choices(context) : List.of();
  }

  @Override
  public Iterator<String> iterator() {
    return values.iterator();
  }
}
