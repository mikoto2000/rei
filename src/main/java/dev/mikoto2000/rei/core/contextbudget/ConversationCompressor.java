package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

@FunctionalInterface
public interface ConversationCompressor {
  /**
   * Provider completion usage, when present; null preserves conservative validation for legacy
   * compressors.
   */
  record Summary(String text, Integer outputTokens) {}

  String summarize(
      String previousSummary, List<Message> newlyCompressible, int maxTokens, Prompt ownerRequest);

  default String summarize(
      String previousSummary,
      List<Message> newlyCompressible,
      int maxTokens,
      Prompt ownerRequest,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if (budget != null)
      throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
          dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    return summarize(previousSummary, newlyCompressible, maxTokens, ownerRequest);
  }

  default Summary summarizeResult(
      String previousSummary, List<Message> newlyCompressible, int maxTokens, Prompt ownerRequest) {
    return new Summary(
        summarize(previousSummary, newlyCompressible, maxTokens, ownerRequest), null);
  }

  default Summary summarizeResult(
      String previousSummary,
      List<Message> newlyCompressible,
      int maxTokens,
      Prompt ownerRequest,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    return new Summary(
        summarize(previousSummary, newlyCompressible, maxTokens, ownerRequest, budget), null);
  }
}
