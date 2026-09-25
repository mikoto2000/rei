package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.mikoto2000.rei.activity.ActivityEvidenceDisplayFormatter.Source;
/** Derived summary relation. Support identifies saved observations, never a session-wide cross product. */
public record ProjectThemeAssociation(String canonicalProject,String theme,int supportingObservationCount,
    long supportingDuration,@JsonIgnore int foregroundObservationCount,@JsonIgnore int supportingSessionCount,@JsonIgnore long longestContinuousSeconds,
    @JsonIgnore double projectConfidence,@JsonIgnore double themeConfidence,double associationConfidence,@JsonIgnore Set<Source> evidenceSources,
    @JsonIgnore List<Support> provenance) {
  public enum Origin { SAME_ACTIVITY_TITLE, SINGLE_ACTIVITY_SUMMARY }
  public record Support(String observationId,String monitor,String continuityId,Instant start,Instant end,
      Origin origin,boolean foreground,double projectConfidence,double themeConfidence,Set<Source> evidenceSources) {}
  public boolean strong(){return associationConfidence>=.75;}
  static ProjectThemeAssociation summarize(String project,String theme,Collection<Support> input) {
    var supports=input.stream().sorted(Comparator.comparing(Support::start).thenComparing(Support::observationId)).toList();
    long seconds=0,foregroundSeconds=0,directSeconds=0,longest=0,run=0;
    double projectSum=0,themeSum=0;
    var observations=new HashSet<String>();var foreground=new HashSet<String>();var sessions=new HashSet<String>();
    var sources=EnumSet.noneOf(Source.class);Support previous=null;
    for(var s:supports) {
      long duration=Duration.between(s.start(),s.end()).getSeconds();
      seconds+=duration;observations.add(s.observationId());sessions.add(s.continuityId());sources.addAll(s.evidenceSources());
      if(s.foreground()){foreground.add(s.observationId());foregroundSeconds+=duration;}
      if(s.origin()==Origin.SAME_ACTIVITY_TITLE)directSeconds+=duration;
      projectSum+=s.projectConfidence()*duration;themeSum+=s.themeConfidence()*duration;
      boolean joins=previous!=null && Objects.equals(previous.continuityId(),s.continuityId())
          && Duration.between(previous.end(),s.start()).getSeconds()<=120;
      run=joins?run+duration:duration;longest=Math.max(longest,run);previous=s;
    }
    double pc=seconds==0?0:projectSum/seconds,tc=seconds==0?0:themeSum/seconds;
    double direct=seconds==0?0:directSeconds/(double)seconds,fg=seconds==0?0:foregroundSeconds/(double)seconds;
    double confidence=.35*direct+.15*fg+.15*pc+.15*tc+.1*Math.min(1,observations.size()/3.0)
        +.05*Math.min(1,seconds/300.0)+.05*Math.min(1,longest/300.0);
    // More session-level or background evidence must never compensate for missing direct foreground support.
    if(direct<.8 || fg<.8)confidence=Math.min(confidence,.44);
    else if((observations.size()<3 && seconds<300) || Math.min(pc,tc)<.75)confidence=Math.min(confidence,.69);
    return new ProjectThemeAssociation(project,theme,observations.size(),seconds,foreground.size(),sessions.size(),longest,
        pc,tc,Math.min(1,confidence),Set.copyOf(sources),List.copyOf(supports));
  }
}
