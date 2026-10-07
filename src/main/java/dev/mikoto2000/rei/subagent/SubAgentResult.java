package dev.mikoto2000.rei.subagent;

import java.time.Instant;
import java.util.List;

public record SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
    Instant startedAt, Instant completedAt, SubAgentOutput structuredOutput, List<ValidationError> validationErrors,
    int repairAttempts, List<List<ValidationError>> validationHistory,int modelRetryAttempts,List<String> modelRetryHistory,int toolRetryAttempts,List<String> toolRetryHistory,String durableTaskId) {
  public SubAgentResult {
    validationErrors = List.copyOf(validationErrors);
    validationHistory = validationHistory.stream().map(List::copyOf).toList();
    if (repairAttempts < 0 || repairAttempts > 3) throw new IllegalArgumentException("Invalid repair attempts");
    modelRetryHistory=List.copyOf(modelRetryHistory);
    if(modelRetryAttempts<0||modelRetryAttempts>3||modelRetryHistory.size()>4)throw new IllegalArgumentException("Invalid model retry history");
    toolRetryHistory=List.copyOf(toolRetryHistory);if(toolRetryAttempts<0||toolRetryAttempts>3||toolRetryHistory.size()>4)throw new IllegalArgumentException("Invalid Tool retry history");
  }
  public SubAgentResult(String agentId,String subAgentRunId,Status status,String output,Instant startedAt,Instant completedAt,SubAgentOutput structuredOutput,List<ValidationError> validationErrors,int repairAttempts,List<List<ValidationError>> validationHistory,int modelRetryAttempts,List<String> modelRetryHistory,int toolRetryAttempts,List<String> toolRetryHistory){this(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,modelRetryAttempts,modelRetryHistory,toolRetryAttempts,toolRetryHistory,null);}
  public SubAgentResult withDurableTask(String id){return new SubAgentResult(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,modelRetryAttempts,modelRetryHistory,toolRetryAttempts,toolRetryHistory,id);}
  public SubAgentResult(String agentId,String subAgentRunId,Status status,String output,Instant startedAt,Instant completedAt,SubAgentOutput structuredOutput,List<ValidationError> validationErrors,int repairAttempts,List<List<ValidationError>> validationHistory,int modelRetryAttempts,List<String> modelRetryHistory){this(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,modelRetryAttempts,modelRetryHistory,0,List.of());}
  public SubAgentResult(String agentId,String subAgentRunId,Status status,String output,Instant startedAt,Instant completedAt,SubAgentOutput structuredOutput,List<ValidationError> validationErrors,int repairAttempts,List<List<ValidationError>> validationHistory){this(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,0,List.of());}
  public SubAgentResult withModelRetries(int attempts,List<String> history){return new SubAgentResult(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,attempts,history,toolRetryAttempts,toolRetryHistory,durableTaskId);}
  public SubAgentResult withToolRetries(int attempts,List<String> history){return new SubAgentResult(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,modelRetryAttempts,modelRetryHistory,attempts,history,durableTaskId);}
  public SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
      Instant startedAt, Instant completedAt, SubAgentOutput structuredOutput, List<ValidationError> validationErrors) {
    this(agentId, subAgentRunId, status, output, startedAt, completedAt, structuredOutput, validationErrors, 0, List.of());
  }
  public SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
      Instant startedAt, Instant completedAt) {
    this(agentId, subAgentRunId, status, output, startedAt, completedAt, null, List.of());
  }
  public enum Status { COMPLETED, FAILED, MAX_STEPS_EXCEEDED, TIMEOUT, CANCELLED, UNKNOWN_AGENT }
}
