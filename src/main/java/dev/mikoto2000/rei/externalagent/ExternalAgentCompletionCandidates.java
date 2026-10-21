package dev.mikoto2000.rei.externalagent;

import dev.mikoto2000.rei.core.completion.CompletionCandidate;
import dev.mikoto2000.rei.core.completion.CompletionContext;
import dev.mikoto2000.rei.core.completion.CompletionMetadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 外部エージェント入力補完で利用できる入力候補を提供する。
 *
 * <p>Metadata for the existing variadic /agent grammar, limited to its supported domain actions.
 */
public final class ExternalAgentCompletionCandidates implements CompletionMetadata {
  /** コマンド操作一覧。 */
  private static final ExternalAgentRequest.Action[] COMMAND_ACTIONS = {
    ExternalAgentRequest.Action.REVIEW, ExternalAgentRequest.Action.IMPLEMENT
  };

  /** 外部エージェントへの委譲で用いる種別一覧を返す。 */
  @Override
  public Set<String> types(CompletionContext context) {
    return context.argumentIndex() >= 2
            && valid(context, 1, ExternalAgentRequest.Agent.values())
            && (valid(context, 2, COMMAND_ACTIONS) || materialReview(context))
        ? Set.of("file-or-directory")
        : Set.of("choices");
  }

  /** 外部エージェントへの委譲で用いる選択肢を返す。 */
  @Override
  public List<CompletionCandidate> choices(CompletionContext context) {
    if (context.argumentIndex() == 0)
      return candidates(ExternalAgentRequest.Agent.values(), "agent");
    if (context.argumentIndex() == 1 && valid(context, 1, ExternalAgentRequest.Agent.values())) {
      var actions = new ArrayList<>(candidates(COMMAND_ACTIONS, "action"));
      if (context.tokens().get(1).equals("codex"))
        actions.add(CompletionCandidate.value("material-review", "action"));
      actions.add(CompletionCandidate.value("implementation", "action"));
      actions.add(CompletionCandidate.value("merge", "action"));
      return List.copyOf(actions);
    }
    return List.of();
  }

  /** 外部エージェントへの委譲において、教材レビューに該当するか判定する。 */
  private boolean materialReview(CompletionContext context) {
    return context.tokens().size() > 2
        && context.tokens().get(1).equals("codex")
        && context.tokens().get(2).equals("material-review");
  }

  /** 外部エージェントへの委譲において、有効に該当するか判定する。 */
  private boolean valid(CompletionContext context, int index, Enum<?>[] values) {
    return context.tokens().size() > index
        && Arrays.stream(values)
            .anyMatch(
                value -> value.name().toLowerCase(Locale.ROOT).equals(context.tokens().get(index)));
  }

  /** 外部エージェントへの委譲で用いる候補一覧を返す。 */
  private List<CompletionCandidate> candidates(Enum<?>[] values, String kind) {
    return Arrays.stream(values)
        .map(value -> CompletionCandidate.value(value.name().toLowerCase(Locale.ROOT), kind))
        .toList();
  }
}
