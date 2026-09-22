package dev.mikoto2000.rei.core.completion;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Highest supporting priority wins, even when empty. Ties merge; first value wins duplicates. */
public final class CompletionEngine {
  private final List<CompletionProvider> providers = new CopyOnWriteArrayList<>();
  public CompletionEngine register(CompletionProvider provider) {
    providers.add(Objects.requireNonNull(provider));
    return this;
  }
  public List<CompletionCandidate> complete(CompletionContext context) {
    var selected = new ArrayList<CompletionProvider>();
    int priority = Integer.MIN_VALUE;
    for (var provider : providers) {
      try {
        if (!provider.supports(context)) continue;
        int next = provider.priority();
        if (next > priority) { selected.clear(); priority = next; }
        if (next == priority) selected.add(provider);
      } catch (RuntimeException ignored) { /* A broken extension must not disable input. */ }
    }
    var result = new LinkedHashMap<String, CompletionCandidate>();
    for (var provider : selected) {
      try {
        for (var candidate : provider.complete(context)) result.putIfAbsent(candidate.value(), candidate);
      } catch (RuntimeException ignored) { /* Keep candidates from healthy providers. */ }
    }
    return List.copyOf(result.values());
  }
}
