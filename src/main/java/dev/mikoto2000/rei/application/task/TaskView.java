package dev.mikoto2000.rei.application.task;

import java.time.Instant;
import java.util.List;

/** Transport projection only; execution and transitions belong to the referenced source. */
public record TaskView(String id,String kind,String sourceId,String projectId,String sessionId,
    String runId,String status,String mode,Instant startedAt,Instant updatedAt,String waitingReason,
    String errorSummary,Progress progress,List<Reference> results,String goalId,List<String> dependencyIds,
    String schedulerId,String parentId,List<String> childIds,String checkpointTaskId,
    boolean cancelSupported,boolean resumeSupported,boolean inputSupported,long revision) {
  public record Progress(int completed,int total) {}
  public record Reference(String kind,String id) {}
  public TaskView withResults(List<Reference> references) {
    return new TaskView(id,kind,sourceId,projectId,sessionId,runId,status,mode,startedAt,updatedAt,waitingReason,errorSummary,
        progress,references,goalId,dependencyIds,schedulerId,parentId,childIds,checkpointTaskId,cancelSupported,resumeSupported,inputSupported,revision);
  }
  public TaskView { results=List.copyOf(results);dependencyIds=List.copyOf(dependencyIds);childIds=List.copyOf(childIds); }
  @com.fasterxml.jackson.annotation.JsonProperty("suspendSupported")
  public boolean suspendSupported() {
    return cancelSupported&&runId!=null&&("CHECKPOINT".equals(kind)||"GOAL".equals(kind)&&"RUNNING".equals(status));
  }
  public TaskView links(String goal,List<String> dependencies,String scheduler,String parent,List<String> children) {
    return new TaskView(id,kind,sourceId,projectId,sessionId,runId,status,mode,startedAt,updatedAt,waitingReason,errorSummary,
        progress,results,goal,dependencies,scheduler,parent,children,checkpointTaskId,cancelSupported,resumeSupported,inputSupported,revision);
  }
}
