package dev.mikoto2000.rei.event;
/** Additive completion phase; old event JSON and Java constructors remain supported. */
public record GoalLifecyclePayload(String goalId,String status,int attempts,int maxRuns,int llmCallsUsed,
    int maxLlmCalls,String reason,String completionPhase) implements AgentEventPayload {
  public GoalLifecyclePayload(String goalId,String status,int attempts,int maxRuns,int used,int calls,String reason){this(goalId,status,attempts,maxRuns,used,calls,reason,null);}
  public GoalLifecyclePayload {if(completionPhase==null)completionPhase=java.util.Set.of("WAITING_APPROVAL","PAUSED").contains(status)?"WAITING":status;}
}
