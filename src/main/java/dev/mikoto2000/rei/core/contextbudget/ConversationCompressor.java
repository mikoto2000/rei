package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

@FunctionalInterface
public interface ConversationCompressor {
  String summarize(String previousSummary, List<Message> newlyCompressible, int maxTokens, Prompt ownerRequest);
  default String summarize(String previousSummary,List<Message> newlyCompressible,int maxTokens,Prompt ownerRequest,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if(budget!=null)throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    return summarize(previousSummary,newlyCompressible,maxTokens,ownerRequest);
  }
}
