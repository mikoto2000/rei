package dev.mikoto2000.rei.goal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.event.*;

@Component
public class GoalEvents {
  private final AgentEventPublisher publisher;
  private final Clock clock;
  public GoalEvents(AgentEventPublisher publisher,Clock clock){this.publisher=publisher;this.clock=clock;}
  public void publish(GoalRepository.Goal goal) { publish(goal,null); }
  public void publish(GoalRepository.Goal goal,String completionPhase) {
    publisher.publish(new AgentEvent(UUID.randomUUID().toString(),0,clock.instant(),AgentEventType.GOAL_UPDATED,1,goal.sessionId(),null,
        goal.currentRunId(),goal.id(),null,new GoalLifecyclePayload(goal.id(),goal.status(),goal.attempts(),goal.maxRuns(),
        goal.llmCallsUsed(),goal.maxLlmCalls(),goal.reason(),completionPhase),goal.projectId()));
  }
}
