package dev.mikoto2000.rei.topic;

/** 話題の管理に必要な話題Orchestratorを扱う。 */
public interface TopicOrchestrator {
  /** 通知に応じて状態や表示を更新するため、チャット完了を受け付ける。 */
  void onChatCompleted();

  /** 通知に応じて状態や表示を更新するため、利用者操作がない状態を受け付ける。 */
  TopicGeneratorService.TopicRunResult onUserIdle();
}
