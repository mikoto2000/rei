package dev.mikoto2000.rei.core.contextbudget;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

@FunctionalInterface
public interface ConversationCompressor {
  String summarize(String previousSummary, List<Message> newlyCompressible, int maxTokens, Prompt ownerRequest);
}
