package dev.mikoto2000.rei.checkpoint;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Execution evidence only. No reasoning trace or raw tool output is stored here. */
public record PersistentCheckpoint(int schemaVersion,long revision,Instant createdAt,String taskId,
    String projectId,String projectRoot,String sessionId,String originalRunId,String runId,
    String resumedFromRunId,Long resumedFromRevision,String request,List<String> instructions,
    List<String> acceptanceCriteria,List<Map<String,Object>> plan,String currentStep,
    String status,String interruptionReason,String nextAction,List<Evidence> evidence,
    List<Operation> operations,Map<String,String> files,Map<String,String> git,List<ProcessRef> processes,
    List<String> blockers,CheckpointReconciler.Result reconciliation,dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode) {
  public PersistentCheckpoint {
    mode=mode==null?dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE:mode;
    if(schemaVersion<1||revision<0||createdAt==null||taskId==null||taskId.isBlank()||projectId==null||projectId.isBlank()
        ||projectRoot==null||sessionId==null||originalRunId==null||runId==null||request==null||status==null)
      throw new IllegalArgumentException("Invalid checkpoint identity/schema");
    instructions=List.copyOf(instructions);acceptanceCriteria=List.copyOf(acceptanceCriteria);
    plan=plan.stream().map(Map::copyOf).toList();evidence=List.copyOf(evidence);operations=List.copyOf(operations);
    files=Map.copyOf(files);git=Map.copyOf(git);processes=List.copyOf(processes);blockers=List.copyOf(blockers);
  }
  public enum OperationStatus { PLANNED, STARTED, SUCCEEDED, FAILED, UNKNOWN }
  public PersistentCheckpoint(int schemaVersion,long revision,Instant createdAt,String taskId,
      String projectId,String projectRoot,String sessionId,String originalRunId,String runId,
      String resumedFromRunId,Long resumedFromRevision,String request,List<String> instructions,
      List<String> acceptanceCriteria,List<Map<String,Object>> plan,String currentStep,
      String status,String interruptionReason,String nextAction,List<Evidence> evidence,
      List<Operation> operations,Map<String,String> files,Map<String,String> git,List<ProcessRef> processes,
      List<String> blockers,CheckpointReconciler.Result reconciliation) {
    this(schemaVersion,revision,createdAt,taskId,projectId,projectRoot,sessionId,originalRunId,runId,
        resumedFromRunId,resumedFromRevision,request,instructions,acceptanceCriteria,plan,currentStep,
        status,interruptionReason,nextAction,evidence,operations,files,git,processes,blockers,reconciliation,null);
  }
  public record Evidence(String origin,String summary,String eventId,String runId,String toolCallId) {}
  public record Operation(String toolCallId,String toolName,OperationStatus status,String eventId,String runId) {
    public Operation(String toolCallId,String toolName,OperationStatus status,String eventId){this(toolCallId,toolName,status,eventId,null);}
    public String operationKey(){return runId==null?toolCallId:runId+":"+toolCallId;}
  }
  public record ProcessRef(String processId,long pid,String startedAt,String command,String owner) {}
  public static PersistentCheckpoint initial(String task,String project,String session,String run,Path root,String request) {
    return new PersistentCheckpoint(1,0,Instant.now(),task,project,root.toString(),session,run,run,null,null,
        request,List.of(),List.of(),List.of(),null,"RUNNING",null,"照合し、未完了・未検証の工程から継続",List.of(),List.of(),Map.of(),Map.of(),List.of(),List.of(),null);
  }
  public PersistentCheckpoint revision(long number) {
    return new PersistentCheckpoint(schemaVersion,number,Instant.now(),taskId,projectId,projectRoot,sessionId,originalRunId,runId,
        resumedFromRunId,resumedFromRevision,request,instructions,acceptanceCriteria,plan,currentStep,status,interruptionReason,nextAction,evidence,operations,files,git,processes,blockers,reconciliation,mode);
  }
  public PersistentCheckpoint resume(String newRun) {
    return new PersistentCheckpoint(schemaVersion,revision,createdAt,taskId,projectId,projectRoot,sessionId,originalRunId,newRun,
        runId,revision,request,instructions,acceptanceCriteria,plan,currentStep,"RUNNING",null,nextAction,evidence,
        operations.stream().map(o->o.status()==OperationStatus.STARTED?new Operation(o.toolCallId(),o.toolName(),OperationStatus.UNKNOWN,o.eventId(),o.runId()):o).toList(),files,git,processes,blockers,reconciliation,mode);
  }
}
