package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;

/** Distinct destination reuses the existing role/Run/approval/budget/output verifier. */
public final class CodexAppServerSubAgentBackend {
  private final ChatGptSubAgentBackend delegate;

  public CodexAppServerSubAgentBackend(ChatGptSubAgentBackend delegate) {
    this.delegate = delegate;
  }

  public ChatGptSubAgentBackend.Receipt run(
      ChatGptSubAgentBackend.Request request,
      AgentRunContext owner,
      boolean standalone,
      LlmCallReservation budget) {
    return delegate.run(request, owner, standalone, budget);
  }

  public ChatGptSubAgentBackend.Receipt runAutomatic(
      ChatGptSubAgentBackend.Request request, AgentRunContext owner, LlmCallReservation budget) {
    return delegate.runAutomatic(request, owner, budget);
  }
}
