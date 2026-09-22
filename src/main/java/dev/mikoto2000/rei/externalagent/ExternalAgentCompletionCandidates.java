package dev.mikoto2000.rei.externalagent;

import java.util.*;
import dev.mikoto2000.rei.core.completion.*;

/** Metadata for the existing variadic /agent grammar, sourced from the domain enums. */
public final class ExternalAgentCompletionCandidates implements CompletionMetadata {
  @Override public Set<String> types(CompletionContext context) {
    return context.argumentIndex() >= 2 && valid(context, 1, ExternalAgentRequest.Agent.values())
        && valid(context, 2, ExternalAgentRequest.Action.values()) ? Set.of("file-or-directory") : Set.of("choices");
  }
  @Override public List<CompletionCandidate> choices(CompletionContext context) {
    if (context.argumentIndex() == 0) return candidates(ExternalAgentRequest.Agent.values(), "agent");
    if (context.argumentIndex() == 1 && valid(context, 1, ExternalAgentRequest.Agent.values()))
      return candidates(ExternalAgentRequest.Action.values(), "action");
    return List.of();
  }
  private boolean valid(CompletionContext context, int index, Enum<?>[] values) {
    return context.tokens().size() > index && Arrays.stream(values)
        .anyMatch(value -> value.name().toLowerCase(Locale.ROOT).equals(context.tokens().get(index)));
  }
  private List<CompletionCandidate> candidates(Enum<?>[] values, String kind) {
    return Arrays.stream(values).map(value -> CompletionCandidate.value(value.name().toLowerCase(Locale.ROOT), kind)).toList();
  }
}
