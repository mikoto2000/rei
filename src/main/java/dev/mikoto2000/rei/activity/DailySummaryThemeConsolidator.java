package dev.mikoto2000.rei.activity;
import java.util.*;
import static dev.mikoto2000.rei.activity.DailySummaryAggregate.*;
import static dev.mikoto2000.rei.activity.SummaryThemeCandidate.Level;
import static dev.mikoto2000.rei.activity.SummaryThemeCandidate.DisplaySource;
/** Shared day/bucket display selection over already canonical, quality-checked associations. */
public final class DailySummaryThemeConsolidator {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DailySummaryThemeConsolidator.class);
  public record Metrics(int canonicalCandidates,int groupCandidates,int suppressedChildren,int suppressedTopics,int selectedThemes) {}
  public record Result(List<SummaryThemeCandidate> candidates,Metrics metrics) {
    public List<String> labels(){return candidates.stream().map(SummaryThemeCandidate::label).toList();}
  }
  public Result consolidate(List<ProjectThemeStat> input,SummaryThemeGroups config,int limit) {
    long total=input.stream().mapToLong(ProjectThemeStat::observedSeconds).sum();
    var replacements=new ArrayList<SummaryThemeCandidate>();var grouped=new HashSet<String>();
    var eligible=new LinkedHashMap<String,SummaryThemeGroups.Group>();
    for(var group:config.groups()) {
      var members=input.stream().filter(s->!s.canonicalProject().isBlank() && group.projects().contains(s.canonicalProject())).toList();
      long seconds=members.stream().mapToLong(ProjectThemeStat::observedSeconds).sum();
      double coverage=total==0?0:seconds/(double)total;
      long largest=members.stream().mapToLong(ProjectThemeStat::observedSeconds).max().orElse(0);
      boolean sufficient=seconds>=600 && coverage>=.15 && (members.size()>=2 && largest<=seconds*.9
          || members.size()==1 && group.allowSingleProject());
      if(!sufficient)continue;
      var activities=new HashMap<String,Long>();
      members.forEach(s->s.categorySeconds().forEach((c,d)->activities.merge(c,d,Long::sum)));
      var categories=DailySummaryAggregator.top(activities,2).stream().filter(c->c.seconds()>=Math.min(120,seconds*.2)).map(Weighted::name).toList();
      String detail=String.join("・",categories.stream().map(DailySummaryAggregate::categoryLabel).toList());
      String label=group.displayName()+(group.displayName().endsWith(detail)?"":"の"+detail);
      double confidence=members.stream().mapToDouble(s->confidence(s)*s.observedSeconds()).sum()/seconds;
      // Explicit topic containment contributes to display ranking, not project duration or association confidence.
      double themeSupport=input.stream().filter(s->s.canonicalProject().isBlank() && !s.themeCandidates().isEmpty())
          .mapToDouble(s->score(s)*s.themeCandidates().stream().filter(group.themes()::contains).count()/s.themeCandidates().size()).sum();
      double score=members.stream().mapToDouble(DailySummaryThemeConsolidator::score).sum()*(1+.15*coverage)+themeSupport;
      replacements.add(new SummaryThemeCandidate("group:"+group.id(),group.displayName(),Level.GROUP,DisplaySource.THEME_GROUP,List.of(group.id()),
          members.stream().sorted(Comparator.comparingLong(ProjectThemeStat::observedSeconds).reversed().thenComparing(ProjectThemeStat::canonicalProject))
              .map(ProjectThemeStat::canonicalProject).limit(2).toList(),members.size(),categories,List.of(),seconds,
          members.stream().mapToInt(ProjectThemeStat::observationCount).sum(),
          members.stream().mapToLong(ProjectThemeStat::longestContinuousSeconds).max().orElse(0),confidence,2,coverage,score,label));
      members.forEach(s->grouped.add(s.canonicalProject()));eligible.put("group:"+group.id(),group);
    }
    var base=new ArrayList<SummaryThemeCandidate>(replacements);
    for(var stat:input)if(!grouped.contains(stat.canonicalProject()))base.add(resolveDisplay(project(stat,config),config));
    // A retained project also represents its explicit group for display de-duplication.
    // Only selected representatives cover topics; unrelated and unselected groups never suppress them.
    // Topic scores can only decrease, so retained project/group representatives grow monotonically.
    log.debug("[summary-trace] grouped-themes eligible={} replacedProjects={}",eligible.keySet(),grouped);
    log.debug("[summary-theme] scored candidates={}",base);
    Set<String> parents=Set.of();List<SummaryThemeCandidate> selected=List.of();int suppressedTopics=0;
    for(int iteration=0;iteration<=config.groups().size()+1;iteration++) {
      var covered=new HashSet<String>();
      for(var group:config.groups())if(parents.contains(group.id()))covered.addAll(group.themes());
      var candidates=new ArrayList<SummaryThemeCandidate>();suppressedTopics=0;
      for(var c:base) {
        if(c.level()!=Level.THEME){candidates.add(c);continue;}
        var remaining=c.themes().stream().filter(t->!covered.contains(t)).toList();
        suppressedTopics+=c.themes().size()-remaining.size();
        if(!remaining.isEmpty())candidates.add(new SummaryThemeCandidate("theme:"+String.join("・",remaining),String.join("・",remaining),c.level(),c.displaySource(),config.idsFor("",remaining),c.memberProjects(),
            c.memberProjectCount(),c.activities(),remaining,c.durationSeconds(),c.observationCount(),c.longestContinuousSeconds(),
            c.associationConfidence(),c.specificity(),c.groupCoverage(),c.score()*remaining.size()/c.themes().size(),String.join("・",remaining)));
      }
      boolean specific=candidates.stream().anyMatch(c->c.level()!=Level.GENERIC);
      var unique=new LinkedHashMap<String,SummaryThemeCandidate>();
      candidates.stream().filter(c->!specific || c.level()!=Level.GENERIC)
          .sorted(Comparator.comparingDouble(SummaryThemeCandidate::score).reversed().thenComparing(SummaryThemeCandidate::label))
          .forEach(c->unique.putIfAbsent(c.label(),c));
      selected=unique.values().stream().limit(limit).toList();
      var next=new HashSet<String>();selected.stream().filter(c->c.level()==Level.GROUP || c.level()==Level.PROJECT)
          .forEach(c->next.addAll(c.groupIds()));
      if(next.equals(parents))break;parents=Set.copyOf(next);
    }
    log.debug("[summary-trace] suppressed-themes projects={} reason=PARENT_GROUP_SELECTED coveredGroupIds={} topicCount={} topicReason=GROUP_REPRESENTATIVE_SELECTED",grouped,parents,suppressedTopics);
    log.debug("[summary-trace] final-themes candidates={}",selected);
    var metrics=new Metrics(input.size(),eligible.size(),grouped.size(),suppressedTopics,selected.size());
    log.debug("Summary theme consolidation canonical={} groups={} suppressedChildren={} suppressedTopics={} selected={}",
        metrics.canonicalCandidates(),metrics.groupCandidates(),metrics.suppressedChildren(),metrics.suppressedTopics(),metrics.selectedThemes());
    return new Result(selected,metrics);
  }
  private static SummaryThemeCandidate resolveDisplay(SummaryThemeCandidate candidate,SummaryThemeGroups config) {
    if(candidate.level()!=Level.PROJECT)return candidate;
    var group=config.groups().stream().filter(g->g.allowSingleProject() && candidate.groupIds().contains(g.id())).findFirst().orElse(null);
    if(group==null)return candidate;
    // Membership controls presentation independently of group aggregation eligibility.
    // Categories are observed activities; configured topics describe containment, not a new activity.
    String detail=String.join("・",candidate.activities().stream().limit(2).map(DailySummaryAggregate::categoryLabel).toList());
    String label=group.displayName()+(detail.isBlank() || group.displayName().endsWith(detail)?"":"の"+detail);
    log.debug("[summary-theme-display] canonicalProject={} group={} allowSingleProject=true resolvedDisplay={}",
        candidate.memberProjects(),group.id(),group.displayName());
    return new SummaryThemeCandidate(candidate.id(),group.displayName(),candidate.level(),DisplaySource.THEME_GROUP,
        candidate.groupIds(),candidate.memberProjects(),candidate.memberProjectCount(),candidate.activities(),candidate.themes(),
        candidate.durationSeconds(),candidate.observationCount(),candidate.longestContinuousSeconds(),candidate.associationConfidence(),
        candidate.specificity(),candidate.groupCoverage(),candidate.score(),label);
  }
  private static double confidence(ProjectThemeStat s) {
    return s.strongAssociations().stream().mapToDouble(ProjectThemeAssociation::associationConfidence).average().orElse(0);
  }
  private static double score(ProjectThemeStat s){return s.score()*(s.strongAssociations().isEmpty()?1:.9+.1*confidence(s));}
  private static SummaryThemeCandidate project(ProjectThemeStat s,SummaryThemeGroups config) {
    var level=!s.canonicalProject().isBlank()?Level.PROJECT:!s.themeCandidates().isEmpty()?Level.THEME:Level.GENERIC;
    return new SummaryThemeCandidate("candidate:"+s.label(),s.canonicalProject().isBlank()?s.label():s.canonicalProject(),level,level==Level.PROJECT?DisplaySource.PROJECT:DisplaySource.GENERIC_THEME,config.idsFor(s.canonicalProject(),s.themeCandidates()),
        s.canonicalProject().isBlank()?List.of():List.of(s.canonicalProject()),s.canonicalProject().isBlank()?0:1,
        s.categories(),s.themeCandidates(),s.observedSeconds(),s.observationCount(),s.longestContinuousSeconds(),
        confidence(s),s.specificity(),0,score(s),s.label());
  }
}
