package dev.mikoto2000.rei.externalagent;

import dev.mikoto2000.rei.llm.ModelCallBudget;
import java.util.function.BooleanSupplier;

/**
 * 外部エージェントへの委譲に必要な振り分け外部エージェント実行先を扱う。
 *
 * <p>Provider selection is an authorized domain request, never an arbitrary executable argument.
 */
public final class RoutingExternalAgentExecutor implements ExternalAgentExecutor {
  /** 依頼で選択されたエージェントへ処理を渡すための、Codex と Claude の実行窓口。 */
  private final ExternalAgentExecutor codex, claude;

  /** 指定された値や利用先を使って、RoutingExternalAgentExecutorを初期化する。 */
  public RoutingExternalAgentExecutor(ExternalAgentExecutor codex, ExternalAgentExecutor claude) {
    this.codex = codex;
    this.claude = claude;
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  private ExternalAgentExecutor select(ExternalAgentRequest request) {
    return switch (request.agent()) {
      case CODEX -> codex;
      case CLAUDE -> claude;
    };
  }

  /** 要求された処理を実行する。 */
  @Override
  public ExternalAgentResult execute(ExternalAgentRequest request, BooleanSupplier cancelled) {
    return select(request).execute(request, cancelled);
  }

  /** 要求された処理を実行する。 */
  @Override
  public ExternalAgentResult execute(
      ExternalAgentRequest request, BooleanSupplier cancelled, ModelCallBudget budget) {
    return select(request).execute(request, cancelled, budget);
  }

  /** 外部エージェントへの委譲において、継続入力への対応可否を返す。 */
  @Override
  public boolean supportsContinuation() {
    return codex.supportsContinuation();
  }

  /** 外部エージェントへの委譲において、継続入力への対応可否を返す。 */
  @Override
  public boolean supportsContinuation(ExternalAgentRequest.Agent agent) {
    return agent == ExternalAgentRequest.Agent.CODEX
        ? codex.supportsContinuation()
        : agent == ExternalAgentRequest.Agent.CLAUDE && claude.supportsContinuation();
  }
}
