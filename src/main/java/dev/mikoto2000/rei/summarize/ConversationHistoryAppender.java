package dev.mikoto2000.rei.summarize;

public interface ConversationHistoryAppender {

  void appendUserMessage(String content);

  void appendAssistantMessage(String content);
}
