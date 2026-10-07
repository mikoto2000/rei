package dev.mikoto2000.rei.externalagent;

import java.util.*;
import dev.mikoto2000.rei.core.completion.*;

/** Metadata for the existing variadic /agent grammar, limited to its supported domain actions. */
public final class ExternalAgentCompletionCandidates implements CompletionMetadata {
  private static final ExternalAgentRequest.Action[] COMMAND_ACTIONS={ExternalAgentRequest.Action.REVIEW,ExternalAgentRequest.Action.IMPLEMENT};
  @Override public Set<String> types(CompletionContext context) {
    return context.argumentIndex() >= 2 && valid(context, 1, ExternalAgentRequest.Agent.values())
        && (valid(context, 2, COMMAND_ACTIONS) || materialReview(context)) ? Set.of("file-or-directory") : Set.of("choices");
  }
  @Override public List<CompletionCandidate> choices(CompletionContext context) {
    if (context.argumentIndex() == 0) return candidates(ExternalAgentRequest.Agent.values(), "agent");
    if (context.argumentIndex() == 1 && valid(context, 1, ExternalAgentRequest.Agent.values())) {
      var actions=new ArrayList<>(candidates(COMMAND_ACTIONS,"action"));
      if (context.tokens().get(1).equals("codex")) actions.add(CompletionCandidate.value("material-review","action"));
      actions.add(CompletionCandidate.value("implementation","action"));actions.add(CompletionCandidate.value("merge","action"));return List.copyOf(actions);
    }
    return List.of();
  }
  private boolean materialReview(CompletionContext context) {
    return context.tokens().size() > 2 && context.tokens().get(1).equals("codex") && context.tokens().get(2).equals("material-review");
  }
  private boolean valid(CompletionContext context, int index, Enum<?>[] values) {
    return context.tokens().size() > index && Arrays.stream(values)
        .anyMatch(value -> value.name().toLowerCase(Locale.ROOT).equals(context.tokens().get(index)));
  }
  private List<CompletionCandidate> candidates(Enum<?>[] values, String kind) {
    return Arrays.stream(values).map(value -> CompletionCandidate.value(value.name().toLowerCase(Locale.ROOT), kind)).toList();
  }
}
