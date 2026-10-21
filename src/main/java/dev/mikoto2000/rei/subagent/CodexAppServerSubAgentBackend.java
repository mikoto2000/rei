package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;

/**
 * 補助エージェントへの委譲に必要なCodexアプリケーションサーバー補助エージェントバックエンドを扱う。
 *
 * <p>Distinct destination reuses the existing role/Run/approval/budget/output verifier.
 */
public final class CodexAppServerSubAgentBackend {
  /** 委譲先。 */
  private final ChatGptSubAgentBackend delegate;

  /** 指定された値や利用先を使って、CodexAppServerSubAgentBackendを初期化する。 */
  public CodexAppServerSubAgentBackend(ChatGptSubAgentBackend delegate) {
    this.delegate = delegate;
  }

  /** 補助エージェントへの委譲の操作を実行する。 */
  public ChatGptSubAgentBackend.Receipt run(
      ChatGptSubAgentBackend.Request request,
      AgentRunContext owner,
      boolean standalone,
      LlmCallReservation budget) {
    return delegate.run(request, owner, standalone, budget);
  }

  /** 依頼された操作を進めるため、自動を実行する。 */
  public ChatGptSubAgentBackend.Receipt runAutomatic(
      ChatGptSubAgentBackend.Request request, AgentRunContext owner, LlmCallReservation budget) {
    return delegate.runAutomatic(request, owner, budget);
  }
}
