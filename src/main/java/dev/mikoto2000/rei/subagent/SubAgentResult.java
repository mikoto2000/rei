package dev.mikoto2000.rei.subagent;

import java.time.Instant;
import java.util.List;

public record SubAgentResult(String agentId, String subAgentRunId, Status status, String output,
    Instant startedAt, Instant completedAt, SubAgentOutput structuredOutput, List<ValidationError> validationErrors,
    int repairAttempts, List<List<ValidationError>> validationHistory,int modelRetryAttempts,List<String> modelRetryHistory) {
  public SubAgentResult {
    validationErrors = List.copyOf(validationErrors);
    validationHistory = validationHistory.stream().map(List::copyOf).toList();
    if (repairAttempts < 0 || repairAttempts > 3) throw new IllegalArgumentException("Invalid repair attempts");
    modelRetryHistory=List.copyOf(modelRetryHistory);
    if(modelRetryAttempts<0||modelRetryAttempts>3||modelRetryHistory.size()>4)throw new IllegalArgumentException("Invalid model retry history");
  }
  public SubAgentResult(String agentId,String subAgentRunId,Status status,String output,Instant startedAt,Instant completedAt,SubAgentOutput structuredOutput,List<ValidationError> validationErrors,int repairAttempts,List<List<ValidationError>> validationHistory){this(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,0,List.of());}
  public SubAgentResult withModelRetries(int attempts,List<String> history){return new SubAgentResult(agentId,subAgentRunId,status,output,startedAt,completedAt,structuredOutput,validationErrors,repairAttempts,validationHistory,attempts,history);}
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
