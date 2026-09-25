package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Failure-isolated operational view; never writes rules or screenshots. */
public final class ClassificationToolkit {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ClassificationToolkit.class);
  private final ActivityProperties p;private final OperationalRules rules;private final ClassificationTelemetryRepository repository;private final Clock clock;
  private final dev.mikoto2000.rei.memory.util.SensitiveInfoDetector sensitive=new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector();
  private final LongAdder classificationSuggestions=new LongAdder(),entertainmentSuggestions=new LongAdder();
  public ClassificationToolkit(ActivityProperties p,OperationalRules rules,ClassificationTelemetryRepository repository,Clock clock){this.p=p;this.rules=rules;this.repository=repository;this.clock=clock;}
  public OperationalRules rules(){return rules;}
  public ActivityProperties properties(){return p;}
  public ActivityClassification classify(ActivityEvidence e){return rules.classify(e);}
  public void poll(){if(p.getClassification().isHotReload())rules.poll();}
  private boolean safe(ActivityRecord r) {
    if(new CapturePolicy(p).excluded(r.foreground()))return false;
    var a=r.inference().activities();String text=r.foreground().windowTitle()+" "+a.toString();
    return !sensitive.containsSensitiveInfo(text) && !text.matches("(?is).*(?:sk-proj-|Bearer\\s+|https?://\\S*[?&](?:key|token|code)=).*" );
  }
  public ActivityRecord decorate(ActivityRecord r) {
    try {
      if(!safe(r) || r.inference().activities().isEmpty())return r;
      var a=r.inference().activities().getFirst();var fields=ActivityEnrichment.fields(r);boolean usable=fields.usable(p.getDetection().getSkipVisionConfidence());
      var d=r.detection();String winning=d==null?"legacy":d.reason().split("\\|",2)[0];
      var matching=OperationalRules.matching(rules.snapshot(),"classification",r.foreground().processName(),r.foreground().windowTitle(),a.service(),a.application(),a.contentTitle(),a.type()).stream().filter(rule->rule.source().equals("user")).map(OperationalRules.Rule::id).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      if(!matching.contains(winning))matching.add(winning);
      String source=rules.snapshot().rules().stream().filter(rule->rule.id().equals(winning)).map(OperationalRules.Rule::source).findFirst().orElse("built-in");
      var reasons=new ArrayList<String>();
      if(!usable)reasons.add(a.type().equals("unknown")?switch(winning){case "GENERIC_BROWSER"->"GENERIC_BROWSER_TITLE";case "GENERIC_TERMINAL"->"GENERIC_WINDOW_TITLE";case "UNRECOGNIZED_PROCESS"->"NO_RULE_MATCH";default->"INSUFFICIENT_EVIDENCE";}:"LOW_CONFIDENCE");
      if(d!=null && d.reason().contains("|VISION_"))reasons.add(d.reason().substring(d.reason().indexOf('|')+1));
      if(d!=null && d.reason().contains("|CONFLICTING_RULES"))reasons.add("CONFLICTING_RULES");
      var entertainment=rules.entertainment(a.service(),a.application(),r.foreground().windowTitle(),a.contentTitle(),a.type());
      boolean required=!usable && p.isExtractionEnabled() && p.getDetection().isVisionEnabled() && p.getDetection().isFallbackEnabled();
      var diagnostics=new ClassificationDiagnostics(List.copyOf(matching),winning,source,fields,usable,required,usable?"CLASSIFICATION_USABLE":required?"INSUFFICIENT_CLASSIFICATION":"VISION_DISABLED",List.copyOf(reasons),entertainment.disposition(),entertainment.confidence(),entertainment.ruleId(),entertainment.source(),entertainment.reason());
      if(d==null)d=new ActivityRecord.Detection(null,List.of(),false,"LEGACY","FINAL",Map.of(),"legacy");
      var updated=new ActivityRecord.Detection(d.evidence(),d.classificationSources(),d.visionUsed(),d.classificationMode(),d.status(),d.sourceConfidence(),d.reason(),fields,d.secondaryConfidence(),diagnostics,d.visionDiagnostics());
      if(p.getClassification().getDiagnostics().isEnabled())log.debug("Activity operational classification: rule={} source={} usable={} visionReason={} entertainment={} entertainmentRule={}",winning,source,usable,diagnostics.visionRequiredReason(),entertainment.disposition(),entertainment.ruleId());
      return new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),r.inference(),r.confidence(),r.screenshotReferences(),r.changeAmount(),r.duplicate(),r.continuityId(),updated);
    }catch(Exception e){log.warn("Activity classification diagnostics unavailable");return r;}
  }
  public void observe(ActivityRecord r) {
    try {
      if(!safe(r) || r.detection()==null || r.detection().diagnostics()==null)return;
      var a=r.inference().activities().getFirst();var d=r.detection();
      repository.save(new ClassificationTelemetryRepository.Row(r.id(),r.capturedAt(),clip(r.foreground().processName()),clip(r.foreground().windowTitle()),clip(a.application()),clip(a.service()),clip(a.contentTitle()),a.type(),d.diagnostics(),d.visionUsed(),d.classificationMode().equals("EVIDENCE_PLUS_VISION"),d.reason().contains("|VISION_")?d.reason().substring(d.reason().indexOf('|')+1):"",d.diagnostics().visionRequired()),p.getClassification(),clock.instant());
    }catch(Exception e){log.warn("Activity classification telemetry unavailable; observation retained");}
  }
  public List<ClassificationTelemetryRepository.Candidate> candidates(String kind) {
    var retention=kind.equals("unknown")?p.getClassification().getUnknownRegistry():p.getClassification().getEntertainmentRegistry();
    if(!retention.isEnabled())return List.of();
    try{return repository.candidates(kind,clock.instant().minus(Duration.ofDays(retention.getRetentionDays()))).stream().filter(c->!sensitive.containsSensitiveInfo(c.title()+c.content()+c.service()) && !new CapturePolicy(p).excluded(new ForegroundWindow(c.process(),1,c.title(),"registry",null))).toList();}
    catch(Exception e){log.warn("Activity classification registry unavailable");return List.of();}
  }
  public Map<String,Long> metrics() {
    var m=new TreeMap<String,Long>();
    for(String name:List.of("observations","evidence_only","vision_fallback","unknown","partial","rule_match","rule_no_match","classification_user","classification_built-in","vision_output_limit","vision_timeout","entertainment","non_entertainment","uncertain","entertainment_user","entertainment_rule_hit"))m.put(name,0L);
    try {
      for(var r:repository.rows(LocalDate.now(clock.withZone(ZoneId.of(p.getZone()))).atStartOfDay(ZoneId.of(p.getZone())).toInstant())) {
        var d=r.diagnostics();inc(m,"observations");inc(m,r.initialVisionRequired()?"vision_fallback":"evidence_only");
        if(r.category().equals("unknown"))inc(m,"unknown");if(d.confidence().partial())inc(m,"partial");
        boolean matched=!Set.of("GENERIC_BROWSER","GENERIC_TERMINAL","UNRECOGNIZED_PROCESS","legacy","insufficient_evidence").contains(d.winningRuleId());
        inc(m,matched?"rule_match":"rule_no_match");if(matched){inc(m,"classification_"+d.source());inc(m,"hit:"+d.winningRuleId());}
        if(r.visionAttempted())inc(m,"vision_observations");if(r.visionSuccess())inc(m,"vision_success");
        if(r.visionFailure().equals("VISION_OUTPUT_LIMIT"))inc(m,"vision_output_limit");if(r.visionFailure().equals("VISION_TIMEOUT"))inc(m,"vision_timeout");
        inc(m,d.entertainmentDisposition().name().toLowerCase(Locale.ROOT));
        if(!d.matchedEntertainmentRuleId().isBlank()){inc(m,"entertainment_rule_hit");inc(m,"hit:"+d.matchedEntertainmentRuleId());if(d.entertainmentSource().equals("user"))inc(m,"entertainment_user");}
      }
    }catch(Exception e){log.warn("Activity classification metrics unavailable");}
    m.put("classification_suggestions",classificationSuggestions.sum());m.put("entertainment_suggestions",entertainmentSuggestions.sum());return m;
  }
  public void suggested(boolean entertainment,int count){(entertainment?entertainmentSuggestions:classificationSuggestions).add(count);}
  public String status() {
    var text=new StringBuilder("Classification\nRules file: "+rules.path()+"\nLast reload: "+rules.reloadStatus()+"\n");
    for(String type:List.of("classification","entertainment"))for(String source:List.of("built-in","user"))text.append(type).append(' ').append(source).append(": ").append(rules.snapshot().rules().stream().filter(r->r.type().equals(type) && r.source().equals(source)).count()).append('\n');
    var m=metrics();long n=m.getOrDefault("observations",0L);text.append("Today (retained telemetry):\n");
    m.forEach((key,value)->{if(!key.startsWith("hit:"))text.append("  ").append(key).append(": ").append(value).append('\n');});
    for(String key:List.of("evidence_only","vision_fallback","unknown","vision_observations"))text.append(key).append(" rate: ").append(String.format(Locale.ROOT,"%.1f%%",n==0?0:100.0*m.getOrDefault(key,0L)/n)).append('\n');
    return text.toString();
  }
  public String listRules(){var m=metrics();return rules.snapshot().rules().stream().map(r->r.id()+" type="+r.type()+" source="+r.source()+" priority="+r.priority()+" enabled="+r.enabled()+" hits(today)="+m.getOrDefault("hit:"+r.id(),0L)).collect(java.util.stream.Collectors.joining("\n"));}
  public String listCandidates(String kind,int top){return candidates(kind).stream().limit(Math.max(1,Math.min(100,top))).map(c->c.process()+" / "+c.service()+"\n  "+c.title()+"\n  count="+c.count()+" open="+c.open()+" state="+c.state()+" last="+c.lastSeen()+"\n  category="+c.category()+" disposition="+c.disposition()+" reasons="+c.reasons()+"\n  vision="+c.visionSuccess()+"/"+c.visionAttempted()+" outcomes="+c.outcomes()).collect(java.util.stream.Collectors.joining("\n"));}
  private static String clip(String s){return s==null?"":s.substring(0,Math.min(384,s.length())).replaceAll("[\\p{Cntrl}]"," ");}
  private static void inc(Map<String,Long> m,String key){m.merge(key,1L,Long::sum);}
  public ActivityStore wrap(ActivityStore delegate) {
    return new ActivityStore() {
      public void append(ActivityRecord r){var updated=decorate(r);delegate.append(updated);observe(updated);}
      public void replace(ActivityRecord r){var updated=decorate(r);delegate.replace(updated);observe(updated);}
      public List<ActivitySession> findBetween(Instant a,Instant b){return delegate.findBetween(a,b);}
      public List<ActivityRecord> findRecordsBetween(Instant a,Instant b){return delegate.findRecordsBetween(a,b);}
    };
  }
}
