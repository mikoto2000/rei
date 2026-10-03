package dev.mikoto2000.rei.workcontext;

import java.time.Instant;
import java.util.*;

/** Project work state, separate from chat history, task execution and personal memory. */
public record WorkContext(String projectId,long revision,Instant createdAt,Instant updatedAt,
    GitState git,List<Item> items,Set<String> processedRuns) {
  public WorkContext { items=List.copyOf(items); processedRuns=Set.copyOf(processedRuns); }
  public enum Kind { PURPOSE, CURRENT_WORK, DECISION, COMPLETED_WORK, PENDING, VERIFICATION, BLOCKER, NEXT_ACTION, ARTIFACT }
  public enum Status { OPEN, COMPLETED, WITHDRAWN, SUPERSEDED, UNCONFIRMED }
  public enum Origin { USER, TOOL, ASSISTANT, INFERENCE }
  public record GitState(String directory,String branch,String commit,Instant capturedAt) {}
  public record Evidence(String id,Origin origin,String sessionId,String turnId,String runId,
      String toolCallId,String filePath,String commit,Instant observedAt,Instant acquiredAt,String text) {}
  public record Item(String id,Kind kind,String text,String reason,Status status,List<Evidence> evidence,
      Instant createdAt,Instant updatedAt,boolean userCorrected,String supersededBy,Origin certainty) {
    public Item { evidence=List.copyOf(evidence); if(certainty==null)certainty=Origin.INFERENCE; }
    public Item(String id,Kind kind,String text,String reason,Status status,List<Evidence> evidence,Instant createdAt,Instant updatedAt,boolean userCorrected,String supersededBy) {
      this(id,kind,text,reason,status,evidence,createdAt,updatedAt,userCorrected,supersededBy,
          evidence.stream().map(Evidence::origin).min(java.util.Comparator.naturalOrder()).orElse(Origin.INFERENCE));
    }
  }
}
