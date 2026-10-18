package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class GoalEvents {
  private final AgentEventPublisher publisher;
  private final Clock clock;
  private GoalStageService stages;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureStages(GoalStageService stages) {
    this.stages = stages;
  }

  public GoalEvents(AgentEventPublisher publisher, Clock clock) {
    this.publisher = publisher;
    this.clock = clock;
  }

  public void publish(GoalRepository.Goal goal) {
    publish(goal, null);
  }

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
