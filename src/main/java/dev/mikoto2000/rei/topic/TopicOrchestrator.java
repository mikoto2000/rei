package dev.mikoto2000.rei.topic;

public interface TopicOrchestrator {
  void onChatCompleted();
  TopicGeneratorService.TopicRunResult onUserIdle();
}
