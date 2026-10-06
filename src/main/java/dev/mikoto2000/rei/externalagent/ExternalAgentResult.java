package dev.mikoto2000.rei.externalagent;

import java.util.List;

public record ExternalAgentResult(Status status, String summary, List<ExternalAgentFinding> findings,
    List<String> warnings, long duration, Integer exitCode, String rawOutput, String reviewId, String externalSessionId,
    dev.mikoto2000.rei.core.TextChangeSetService.Request proposedChange, String changeSetId) {
  public ExternalAgentResult(Status status,String summary,List<ExternalAgentFinding> findings,List<String> warnings,long duration,Integer exitCode,String rawOutput,String reviewId,String externalSessionId) {
    this(status,summary,findings,warnings,duration,exitCode,rawOutput,reviewId,externalSessionId,null,null);
  }
  public ExternalAgentResult(Status status,String summary,List<ExternalAgentFinding> findings,List<String> warnings,long duration,Integer exitCode,String rawOutput,String reviewId) {
    this(status,summary,findings,warnings,duration,exitCode,rawOutput,reviewId,null);
  }
  public ExternalAgentResult(Status status,String summary,List<ExternalAgentFinding> findings,List<String> warnings,long duration,Integer exitCode,String rawOutput) {
    this(status,summary,findings,warnings,duration,exitCode,rawOutput,null);
  }
  public enum Status { SUCCESS, SUCCESS_WITH_WARNINGS, FAILED, UNAVAILABLE, TOTAL_TIMEOUT, INACTIVITY_TIMEOUT, CANCELLED, REJECTED }
  public ExternalAgentResult {
    findings = List.copyOf(findings); warnings = List.copyOf(warnings);
    if (!validSessionId(externalSessionId) || (status != Status.SUCCESS && status != Status.SUCCESS_WITH_WARNINGS)) externalSessionId=null;
    if(status!=Status.SUCCESS && status!=Status.SUCCESS_WITH_WARNINGS)proposedChange=null;
  }
  static boolean validSessionId(String value) {
    return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
  public boolean success() { return status == Status.SUCCESS || status == Status.SUCCESS_WITH_WARNINGS; }
  public static ExternalAgentResult rejected(String reason) {
    return new ExternalAgentResult(Status.REJECTED, reason, List.of(), List.of(), 0, null, "");
  }
  /** Only this bounded review, never process logs, is passed into rei's ephemeral reasoning. */
  public ExternalAgentResult forEvaluation() {
    return new ExternalAgentResult(status, summary, findings, warnings, duration, exitCode, "", reviewId, externalSessionId,null,changeSetId);
  }
}
