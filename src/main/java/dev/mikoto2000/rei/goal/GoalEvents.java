package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 目標の達成管理に必要な目標イベント一覧を扱う。 */
@Component
public class GoalEvents {
  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 段階一覧。 */
  private GoalStageService stages;

  /** 目標の達成管理に関する設定段階一覧を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureStages(GoalStageService stages) {
    this.stages = stages;
  }

  /** 指定された値や利用先を使って、GoalEventsを初期化する。 */
  public GoalEvents(AgentEventPublisher publisher, Clock clock) {
    this.publisher = publisher;
    this.clock = clock;
  }

  /** 状態や結果を購読先へ通知する。 */
  public void publish(GoalRepository.Goal goal) {
    publish(goal, null);
  }

  /** 状態や結果を購読先へ通知する。 */
  public void publish(GoalRepository.Goal goal, String completionPhase) {
    publisher.publish(
        new AgentEvent(
            UUID.randomUUID().toString(),
            0,
            clock.instant(),
            AgentEventType.GOAL_UPDATED,
            1,
            goal.sessionId(),
            null,
            goal.currentRunId(),
            goal.id(),
            null,
            new GoalLifecyclePayload(
                goal.id(),
                goal.status(),
                goal.attempts(),
                goal.maxRuns(),
                goal.llmCallsUsed(),
                goal.maxLlmCalls(),
                goal.reason(),
                completionPhase,
                stages == null ? null : stages.eventProgress(goal)),
            goal.projectId()));
  }
}
