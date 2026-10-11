package dev.mikoto2000.rei.episode;

import java.util.*;

/** An immutable event revision. Evidence belongs to each claim, never to the event status. */
public record Episode(String id,String projectId,String sessionId,String revision,String occurredAt,String endedAt,
    Status status,String title,String summary,List<Claim> claims,double confidence) {
  public Episode(String id,String projectId,String sessionId,String revision,String occurredAt,Status status,String title,String summary,List<Claim> claims,double confidence) {
    this(id,projectId,sessionId,revision,occurredAt,null,status,title,summary,claims,confidence);
  }
  public enum Status { IN_PROGRESS, COMPLETED, UNVERIFIED, WITHDRAWN }
  public enum Evidence { USER_EXPLICIT, TOOL_OBSERVED, MODEL_PROPOSAL, DERIVED_SUMMARY, UNVERIFIED }
  public record Claim(String kind,String text,Evidence evidence,String runId,String speaker,String sourceId) {
    public Claim(String kind,String text,Evidence evidence,String runId,String speaker){this(kind,text,evidence,runId,speaker,null);}
    public Claim {
      if(kind==null||!Set.of("background","alternative","decision","reason","action","result","unknown").contains(kind)
          ||text==null||text.isBlank()||text.length()>2000||evidence==null||runId==null||runId.isBlank()
          ||!Set.of("user","assistant","tool").contains(speaker))throw new IllegalArgumentException("Invalid episode claim");
      if(evidence==Evidence.USER_EXPLICIT&&!speaker.equals("user")||evidence==Evidence.TOOL_OBSERVED&&!speaker.equals("tool"))
        throw new IllegalArgumentException("Evidence does not match speaker");
      if(speaker.equals("tool")&&(sourceId==null||sourceId.isBlank()))throw new IllegalArgumentException("Tool evidence needs an event ID");
      if(!speaker.equals("tool")&&sourceId!=null)throw new IllegalArgumentException("Conversation evidence uses the real Run ID only");
    }
  }
  public Episode {
    for(var value:List.of(id,projectId,sessionId,revision,occurredAt,title,summary))
      if(value.isBlank()||value.length()>2000)throw new IllegalArgumentException("Invalid episode field");
    java.time.Instant.parse(occurredAt);
    if(endedAt!=null&&java.time.Instant.parse(endedAt).isBefore(java.time.Instant.parse(occurredAt)))throw new IllegalArgumentException("Invalid episode period");
    claims=List.copyOf(claims);
    if(status==null||claims.isEmpty()||claims.size()>32||!Double.isFinite(confidence)||confidence<0||confidence>1)
      throw new IllegalArgumentException("Invalid episode");
  }
}
