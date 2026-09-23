package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.activity.ActivityRecord.Activity;

/** Two stages: compatible primary contexts, then bounded A/B/A excursions for presentation. */
public record SemanticSessionPolicy(Duration maximumGap,Duration briefSwitch,ZoneId zone) {
  public SemanticSessionPolicy {
    if(maximumGap.isNegative() || briefSwitch.isNegative()) throw new IllegalArgumentException("Negative summary threshold");
  }
  private record Sample(ActivityRecord record,Instant start,Instant end,ActivityRoles roles) {
    long seconds() {return Duration.between(start,end).getSeconds();}
  }
  public List<SummarySegment> aggregate(List<ActivityRecord> records) {
    return aggregate(records,Instant.MIN,Instant.MAX);
  }
  public List<SummarySegment> aggregate(List<ActivityRecord> records,Instant from,Instant until) {
    if(!from.isBefore(until)) throw new IllegalArgumentException("Invalid summary range");
    var sorted=records.stream().sorted(Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id)).toList();
    var groups=new ArrayList<List<Sample>>();var rolePolicy=new ActivityRolePolicy();
    for(int i=0;i<sorted.size();i++) {
      var record=sorted.get(i);
      Instant end=record.capturedAt().plusSeconds(record.durationEstimate());
      Instant midnight=record.capturedAt().atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
      if(end.isAfter(midnight)) end=midnight;
      if(i+1<sorted.size() && end.isAfter(sorted.get(i+1).capturedAt())) end=sorted.get(i+1).capturedAt();
      if(end.isAfter(until)) end=until;
      Instant start=record.capturedAt().isBefore(from)?from:record.capturedAt();
      if(!start.isBefore(end)) continue;
      var sample=new Sample(record,start,end,rolePolicy.classify(record));
      if(!groups.isEmpty() && adjacent(groups.getLast().getLast(),sample) && compatible(groups.getLast(),sample)) groups.getLast().add(sample);
      else groups.add(new ArrayList<>(List.of(sample)));
    }
    // Only a short, observed return to the same context is smoothed. A sustained new primary stays separate.
    for(int i=0;i+2<groups.size();) {
      var a=groups.get(i);var b=groups.get(i+1);var c=groups.get(i+2);
      if(Duration.between(b.getFirst().start(),b.getLast().end()).compareTo(briefSwitch)<=0
          && adjacent(a.getLast(),b.getFirst()) && adjacent(b.getLast(),c.getFirst())
          && c.stream().allMatch(s->compatible(a,s))) {
        a.addAll(b);a.addAll(c);groups.remove(i+2);groups.remove(i+1);
      } else i++;
    }
    return groups.stream().map(this::segment).toList();
  }
  private boolean adjacent(Sample a,Sample b) {
    return Objects.equals(a.record().continuityId(),b.record().continuityId())
        && a.start().atZone(zone).toLocalDate().equals(b.start().atZone(zone).toLocalDate())
        && Duration.between(a.end(),b.start()).compareTo(maximumGap)<=0;
  }
  private boolean compatible(List<Sample> group,Sample sample) {
    Activity primary=group.getFirst().roles().primary(), next=sample.roles().primary();
    if(primary==null || next==null) {
      return primary==null && next==null && Objects.equals(group.getFirst().record().foreground(),sample.record().foreground())
          && new HashSet<>(group.getFirst().record().inference().activities()).equals(new HashSet<>(sample.record().inference().activities()));
    }
    if(!ActivityRolePolicy.sameContext(primary,next)) return false;
    // A missing project candidate must not bridge two conflicting known projects.
    String project=ActivityRolePolicy.normalize(next.projectCandidate());
    return project.isEmpty() || group.stream().map(s->s.roles().primary()).filter(Objects::nonNull)
        .filter(a->ActivityRolePolicy.category(a).equals(ActivityRolePolicy.category(next)))
        .map(a->ActivityRolePolicy.normalize(a.projectCandidate())).filter(p->!p.isEmpty()).allMatch(project::equals);
  }
  private SummarySegment segment(List<Sample> samples) {
    var votes=new LinkedHashMap<Activity,Long>();
    for(var s:samples) if(s.roles().primary()!=null) votes.merge(s.roles().primary(),s.seconds(),Long::sum);
    Activity primary=null;long best=-1;
    for(var candidate:votes.keySet()) {
      long total=votes.entrySet().stream().filter(e->ActivityRolePolicy.sameContext(candidate,e.getKey())).mapToLong(Map.Entry::getValue).sum();
      if(total>best || (total==best && votes.get(candidate)>votes.getOrDefault(primary,0L))) {best=total;primary=candidate;}
    }
    var secondary=new LinkedHashSet<Activity>();var background=new LinkedHashSet<Activity>();
    long seconds=samples.stream().mapToLong(Sample::seconds).sum(); double weighted=0;
    for(var s:samples) {
      if(primary!=null && ActivityRolePolicy.sameRole(primary,s.roles().primary())) weighted+=s.seconds()*s.roles().confidence();
      for(var a:s.record().inference().activities()) {
        if(primary!=null && ActivityRolePolicy.sameRole(primary,a)) continue;
        // A monitor brought to the foreground is a secondary activity during an excursion, not passive background.
        if(ActivityRolePolicy.backgroundCandidate(a) && !a.equals(s.roles().primary())) background.add(a); else secondary.add(a);
      }
    }
    background.removeAll(secondary);
    var roles=new ActivityRoles(primary,List.copyOf(secondary),List.copyOf(background),seconds==0?0:weighted/seconds,
        primary==null?List.of("foreground_unresolved"):List.of("foreground_evidence","observed_duration_weighting"));
    return new SummarySegment(samples.getFirst().start(),samples.getLast().end(),seconds,roles,
        samples.stream().map(Sample::record).toList(),List.of());
  }
}
