package dev.mikoto2000.rei.event;

/** No prompts, intermediate messages or raw exceptions are persisted here. */
public record SubAgentLifecyclePayload(String parentRunId, String subAgentRunId, String agentId,
    String taskSummary, String status, long duration, String failureReason,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String backendId,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String modelId,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer totalTokens) implements AgentEventPayload {
  public SubAgentLifecyclePayload(String parentRunId,String subAgentRunId,String agentId,String taskSummary,String status,long duration,String failureReason){
    this(parentRunId,subAgentRunId,agentId,taskSummary,status,duration,failureReason,null,null,null);
  }
}
