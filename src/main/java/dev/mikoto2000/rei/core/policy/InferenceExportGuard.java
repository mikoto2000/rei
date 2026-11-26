package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.modeloptions.AcceptedRunOptions;
import java.time.Clock;
import org.springframework.ai.chat.prompt.Prompt;

/** Inference export authority is independent of callback execution authority. */
@org.springframework.stereotype.Component
public final class InferenceExportGuard {
  public InferenceExportGuard(ToolApprovalRepository approvals, Clock clock) {}

  public record Decision(Prompt prompt, String approvalId) {
    public boolean permitted() {
      return approvalId == null;
    }

    @Override
    public String toString() {
      return "InferenceExportDecision[permitted="
          + permitted()
          + ", approvalId="
          + approvalId
          + "]";
    }
  }

  public Decision authorize(AgentRunContext owner, AcceptedRunOptions connection, Prompt prompt) {
    throw new UnsupportedOperationException("Inference export authority is not implemented");
  }
}
