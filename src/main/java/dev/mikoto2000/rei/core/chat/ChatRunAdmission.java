package dev.mikoto2000.rei.core.chat;

/** Captures host-owned chat configuration before dispatch and retires its live reference. */
public interface ChatRunAdmission {
  void accept(AgentRunContext owner, String prompt);

  void release(AgentRunContext owner);
}
