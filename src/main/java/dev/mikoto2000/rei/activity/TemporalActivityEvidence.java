package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Bounded chronological aggregation. Adjacent equivalent observations retain every original ID. */
public record TemporalActivityEvidence(Instant windowStart,Instant windowEnd,List<Group> groups,
    List<String> observationIds,List<WorkActivityInference.Execution> executions,String fingerprint) {
  private static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule());
  public record Facts(String process,String title,String windowId,String projectId,String project,String branch,String commit,
      long workRevision,String category,String content,String candidate,double confidence,String visionState,String continuity) {}
  public record Group(Instant first,Instant last,List<String> observationIds,Facts facts) {
    public String projectId(){return facts.projectId();}
  }
  public static TemporalActivityEvidence build(List<ActivityRecord> records,List<WorkActivityInference.Execution> executions,Instant start,Instant end) {
    if(records.size()>120 || executions.size()>64)throw new IllegalArgumentException("Temporal evidence limit exceeded");
    var sorted=records.stream().filter(r->!r.capturedAt().isBefore(start) && !r.capturedAt().isAfter(end))
        .sorted(Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id)).toList();
    var seen=new HashSet<String>();var groups=new ArrayList<Group>();var ids=new ArrayList<String>();
    var events=new LinkedHashMap<String,WorkActivityInference.Execution>();
    for(var execution:executions)if(execution.eventId()!=null && !execution.at().isBefore(start) && !execution.at().isAfter(end))events.put(execution.eventId(),execution);
    for(var r:sorted) {
      if(!seen.add(r.id()))continue;ids.add(r.id());var facts=facts(r);
      if(!groups.isEmpty() && groups.getLast().facts().equals(facts)
          && Duration.between(groups.getLast().last(),r.capturedAt()).getSeconds()<=60) {
        var previous=groups.removeLast();var combined=new ArrayList<>(previous.observationIds());combined.add(r.id());
        groups.add(new Group(previous.first(),r.capturedAt(),List.copyOf(combined),facts));
      }else groups.add(new Group(r.capturedAt(),r.capturedAt(),List.of(r.id()),facts));
      var evidence=r.detection()==null?null:r.detection().evidence();
      if(evidence!=null)for(var e:evidence.events())if(e.eventId()!=null && !e.at().isBefore(start) && !e.at().isAfter(end))
        events.putIfAbsent(e.eventId(),new WorkActivityInference.Execution(e.eventId(),e.at(),e.projectId(),e.kind(),"REI","RECORDED","",e.sessionId(),e.turnId(),e.runId()));
    }
    var ordered=events.values().stream().sorted(Comparator.comparing(WorkActivityInference.Execution::at).thenComparing(WorkActivityInference.Execution::eventId).reversed()).limit(64)
        .sorted(Comparator.comparing(WorkActivityInference.Execution::at).thenComparing(WorkActivityInference.Execution::eventId)).toList();
    // Counts saturate at two: repeated observations add provenance, not a new semantic reason to call a model.
    var semantic=groups.stream().map(g->List.of(g.facts(),Math.min(2,g.observationIds().size()))).toList();
    return new TemporalActivityEvidence(start,end,List.copyOf(groups),List.copyOf(ids),ordered,hash(List.of(semantic,ordered)));
  }
  private static Facts facts(ActivityRecord r) {
    var d=r.detection();var e=d==null?null:d.evidence();var w=e==null?null:e.workContext();var git=w==null?null:w.git();
    var roles=new ActivityRolePolicy().classify(r);var a=roles.primary();var fg=r.foreground();
    var vision=VisionDiagnostics.of(d).foreground();
    return new Facts(clean(fg==null?null:fg.processName()),clean(fg==null?null:fg.windowTitle()),clean(fg==null?null:fg.windowId()),
        clean(e==null?null:e.projectId()),clean(e==null?null:e.projectName()),clean(git==null?null:git.branch()),clean(git==null?null:git.commit()),w==null?0:w.revision(),
        clean(a==null?null:a.type()),clean(a==null?null:a.contentTitle()),clean(a==null?null:a.projectCandidate()),roles.confidence(),
        vision==null?"UNKNOWN":vision.state().name(),clean(r.continuityId()));
  }
  public WorkActivityInference ruleInference() {
    String project="",projectId="",text="観測不足または証拠が混在しているため、具体的な作業は推定不能です。",status="UNKNOWN";double confidence=0;
    if(observationIds.size()>=2 && !groups.isEmpty()) {
      var distinct=groups.stream().map(g->g.facts().projectId()).distinct().toList();var f=groups.getLast().facts();
      if(distinct.size()==1 && !f.projectId().isBlank() && !f.project().isBlank()
          && groups.stream().allMatch(g->g.facts().confidence()>=.5 && !g.facts().category().isBlank() && !"unknown".equals(g.facts().category())
              && ActivityRolePolicy.normalize(g.facts().project()).equals(ActivityRolePolicy.normalize(g.facts().candidate())))) {
        project=f.project();projectId=f.projectId();
        text=project+" の"+(f.content().isBlank()?f.category():"「"+f.content()+"」")+"関連画面を伴う作業の可能性があります。";
        confidence=.6;status="INFERRED";
      }
    }
    if(!executions.isEmpty())text+=" れいの自動実行記録があります（ユーザー操作や成功を示すものではありません）。";
    return new WorkActivityInference(UUID.randomUUID().toString(),fingerprint,windowEnd,windowStart,windowEnd,projectId,project,text,
        observationIds,executions,confidence,status,"RULES",0,0);
  }
  public String structuredInput(int maximum) {
    try {
      String input=JSON.writeValueAsString(Map.of("windowStart",windowStart,"windowEnd",windowEnd,"observations",groups,"agentExecutions",executions));
      if(input.length()>maximum)throw new IllegalArgumentException("Temporal input exceeds character budget");
      return input;
    }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Temporal input encoding failed",e);}
  }
  static String clean(String value) {
    String text=value==null?"":value.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]+"," ").strip();
    text=text.substring(0,Math.min(160,text.length()));
    return new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector().containsSensitiveInfo(text)?"[redacted]":text;
  }
  private static String hash(Object value) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
    catch(Exception e){throw new IllegalStateException("Temporal evidence fingerprint failed",e);}
  }
}
