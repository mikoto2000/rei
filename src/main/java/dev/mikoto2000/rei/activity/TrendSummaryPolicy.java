package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.activity.ActivityRecord.Activity;

/** Read-only trend grouping. Session gap rules remain intact in the source projections. */
public record TrendSummaryPolicy(ZoneId zone,double minimumConfidence,Duration maximumWindow,Duration maximumGap) {
  public TrendSummaryPolicy(ZoneId zone,double minimumConfidence) {this(zone,minimumConfidence,Duration.ofMinutes(60),Duration.ofMinutes(20));}
  public TrendSummaryPolicy {
    new ActivityRolePolicy(minimumConfidence);
    if(maximumWindow.isNegative() || maximumWindow.isZero() || maximumGap.isNegative()) throw new IllegalArgumentException("Invalid trend window");
  }
  private record Sample(ActivityRecord record,Instant start,Instant end,ActivityRoles roles) {
    long seconds() {return Duration.between(start,end).getSeconds();}
    boolean known() {return roles.primary()!=null && !Set.of("unknown","other").contains(roles.category());}
  }
  private record Context(SummarySegment source,List<Sample> samples,Set<String> projects,Set<String> families,Set<String> labels) {}
  public List<TrendSummarySegment> aggregate(List<SummarySegment> source) {
    var groups=new ArrayList<List<Context>>();
    for(var segment:source.stream().sorted(Comparator.comparing(SummarySegment::startedAt)).toList()) {
      var context=context(segment);
      if(context.samples().isEmpty()) continue;
      if(!groups.isEmpty() && joins(groups.getLast(),context)) groups.getLast().add(context);
      else groups.add(new ArrayList<>(List.of(context)));
    }
    return groups.stream().map(this::summarize).toList();
  }
  private Context context(SummarySegment segment) {
    var records=segment.evidence().stream().distinct().sorted(Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id)).toList();
    var samples=new ArrayList<Sample>();var rolePolicy=new ActivityRolePolicy(minimumConfidence);
    var projects=new TreeSet<String>();var families=new TreeSet<String>();var labels=new HashSet<String>();
    for(int i=0;i<records.size();i++) {
      var r=records.get(i);var start=r.capturedAt().isBefore(segment.startedAt())?segment.startedAt():r.capturedAt();
      var end=r.capturedAt().plusSeconds(r.durationEstimate());
      if(end.isAfter(segment.endedAt())) end=segment.endedAt();
      if(i+1<records.size() && end.isAfter(records.get(i+1).capturedAt())) end=records.get(i+1).capturedAt();
      if(!start.isBefore(end)) continue;
      var sample=new Sample(r,start,end,rolePolicy.classify(r));samples.add(sample);
      if(sample.known()) {
        families.add(family(sample.roles().category()));
        if(!sample.roles().primary().projectCandidate().isBlank()) projects.add(sample.roles().primary().projectCandidate());
      }
      for(var a:r.inference().activities()) {String label=ActivityDisplayLabels.candidate(a);if(!label.isBlank()) labels.add(label);}
    }
    return new Context(segment,List.copyOf(samples),projects,families,labels);
  }
  private boolean joins(List<Context> group,Context next) {
    var first=group.getFirst().source();var last=group.getLast().source();var b=next.source();
    if(!first.startedAt().atZone(zone).toLocalDate().equals(b.startedAt().atZone(zone).toLocalDate())) return false;
    if(Duration.between(first.startedAt(),b.endedAt()).compareTo(maximumWindow)>0
        || Duration.between(last.endedAt(),b.startedAt()).compareTo(maximumGap)>0) return false;
    var projects=new HashSet<>(next.projects());group.forEach(c->projects.addAll(c.projects()));
    if(projects.size()>1) return false;
    // Unknowns do not establish a new activity; retain them inside a bounded period with an explicit ratio.
    if(next.families().isEmpty() || group.stream().allMatch(c->c.families().isEmpty())) return true;
    var families=new HashSet<String>();var labels=new HashSet<String>();
    group.forEach(c->{families.addAll(c.families());labels.addAll(c.labels());});
    return !Collections.disjoint(families,next.families()) || !Collections.disjoint(labels,next.labels())
        || (!projects.isEmpty() && projects.equals(next.projects()))
        || next.source().observedSeconds()<=300;
  }
  private static String family(String category) {
    return switch(category) {
      case "development","research","documentation" -> "development-research";
      case "social","media","shopping" -> "web-browsing";
      case "other","unknown" -> "unknown";
      default -> category;
    };
  }
  private static final class LabelScore {
    boolean primary;long seconds;boolean project;
  }
  private TrendSummarySegment summarize(List<Context> group) {
    var samples=group.stream().flatMap(c->c.samples().stream()).sorted(Comparator.comparing(Sample::start)).toList();
    long observed=samples.stream().mapToLong(Sample::seconds).sum();
    long known=samples.stream().filter(Sample::known).mapToLong(Sample::seconds).sum();
    var knownCategories=new TreeSet<String>();var visibleCategories=new TreeSet<String>();var projects=new TreeSet<String>();
    var scores=new HashMap<String,LabelScore>();var continuities=new HashSet<String>();
    for(var s:samples) {
      continuities.add(s.record().continuityId());
      if(s.known()) {
        knownCategories.add(s.roles().category());
        if(!s.roles().primary().projectCandidate().isBlank()) projects.add(s.roles().primary().projectCandidate());
      }
      var observationLabels=new HashSet<String>();
      for(var raw:s.record().inference().activities()) {
        var a=ActivityVocabulary.canonical(raw);
        if(!Set.of("unknown","other","monitoring","idle").contains(a.type())) visibleCategories.add(a.type());
        String label=ActivityDisplayLabels.candidate(raw);if(label.isBlank()) continue;
        var score=scores.computeIfAbsent(label,k->new LabelScore());
        score.primary|=s.known() && a.equals(s.roles().primary());
        if(observationLabels.add(label)) score.seconds+=s.seconds();
        score.project|=s.known() && !a.projectCandidate().isBlank() && a.projectCandidate().equals(s.roles().primary().projectCandidate());
      }
    }
    var categories=known*2>=observed && known>0?knownCategories:visibleCategories;
    var families=new TreeSet<String>();categories.forEach(c->families.add(family(c)));families.remove("unknown");
    String theme=families.isEmpty()?"unknown":families.size()==1?families.getFirst():
        families.stream().allMatch(f->Set.of("development-research","communication").contains(f))?"mixed-work":"mixed";
    var knownFamilies=new HashSet<String>();knownCategories.forEach(c->knownFamilies.add(family(c)));
    Instant start=group.getFirst().source().startedAt(),end=group.getLast().source().endedAt();
    long missing=Math.max(0,Duration.between(start,end).getSeconds()-observed);
    long largestGap=0;for(int i=1;i<samples.size();i++) largestGap=Math.max(largestGap,Duration.between(samples.get(i-1).end(),samples.get(i).start()).getSeconds());
    var continuity=knownFamilies.size()>1?TrendSummarySegment.Continuity.MIXED:
        known==observed && continuities.size()==1 && largestGap<=120 && missing<=Duration.between(start,end).getSeconds()*.1
            ?TrendSummarySegment.Continuity.CONTINUOUS:TrendSummarySegment.Continuity.INTERMITTENT;
    var labels=scores.entrySet().stream().sorted(Comparator.<Map.Entry<String,LabelScore>,Boolean>comparing(e->e.getValue().primary).reversed()
        .thenComparing(Comparator.<Map.Entry<String,LabelScore>>comparingLong(e->e.getValue().seconds).reversed())
        .thenComparing(Comparator.<Map.Entry<String,LabelScore>,Boolean>comparing(e->e.getValue().project).reversed()).thenComparing(Map.Entry::getKey))
        .limit(4).map(Map.Entry::getKey).toList();
    return new TrendSummarySegment(start,end,continuity,theme,observed,known,observed-known,List.copyOf(categories),List.copyOf(projects),labels,
        group.stream().map(Context::source).toList(),samples.stream().map(Sample::record).distinct().toList(),
        group.stream().flatMap(c->c.source().fineSessionIds().stream()).distinct().toList());
  }
}
