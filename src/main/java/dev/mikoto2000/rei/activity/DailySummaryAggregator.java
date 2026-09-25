package dev.mikoto2000.rei.activity;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static dev.mikoto2000.rei.activity.DailySummaryAggregate.*;
/** Read-only arithmetic over saved segment evidence. No model, capture, rule reload or persistence. */
public final class DailySummaryAggregator {
  private final ProjectNameNormalizer names;
  private final ActivityRolePolicy roles;
  private static final Set<String> WORK=Set.of("development","research","documentation");
  public static final int FREQUENT_SWITCHES=10;
  public DailySummaryAggregator(ProjectNameNormalizer names,double minimumConfidence){this.names=names;roles=new ActivityRolePolicy(minimumConfidence);}
  private record Sample(ActivityRecord record,Instant start,Instant end,ActivityRoles roles) {}
  private static final class Counts {
    long observed;
    final Map<String,Long> categories=new HashMap<>(),secondary=new HashMap<>(),background=new HashMap<>();
    final WorkThemeAggregation workThemes=new WorkThemeAggregation();
  }
  public DailySummaryAggregate aggregate(LocalDate date,ActivityQueryRange range,ZoneId zone,List<SummarySegment> source) {
    // A record can appear in multiple projections. Clip it to source coverage, then de-overlap on the global time axis.
    var samples=new HashMap<String,Sample>();
    for(var segment:source)for(var r:segment.evidence()) {
      Instant start=max(max(r.capturedAt(),segment.startedAt()),range.fromInclusive());
      Instant end=min(min(r.capturedAt().plusSeconds(r.durationEstimate()),segment.endedAt()),range.toExclusive());
      if(!start.isBefore(end))continue;
      var prior=samples.get(r.id());
      samples.put(r.id(),new Sample(r,prior==null?start:min(start,prior.start()),prior==null?end:max(end,prior.end()),roles.classify(r)));
    }
    var ordered=samples.values().stream().sorted(Comparator.comparing(Sample::start).thenComparing(s->s.record().id())).toList();
    var categories=new HashMap<String,Long>();var dispositions=new EnumMap<EntertainmentDisposition,Long>(EntertainmentDisposition.class);
    var projects=new HashMap<String,Long>();var services=new HashMap<String,Long>();var themes=new WorkThemeAggregation();var leisure=new HashMap<String,Long>();
    var buckets=new LinkedHashMap<String,Counts>();var workBlocks=new ArrayList<Block>();var leisureBlocks=new ArrayList<Block>();
    long observed=0;int switches=0;String previousProject="";Instant previousEnd=null;
    Block pending=null;boolean pendingWork=false;String pendingContinuity="";
    for(int i=0;i<ordered.size();i++) {
      var s=ordered.get(i);Instant end=s.end();
      if(i+1<ordered.size())end=min(end,ordered.get(i+1).start());
      long seconds=Duration.between(s.start(),end).getSeconds();if(seconds<=0)continue;
      observed+=seconds;String category=s.roles().category();
      if(category.equals("other"))category="unknown";
      categories.merge(category,seconds,Long::sum);
      var d=s.record().detection();var diagnostics=d==null?null:d.diagnostics();
      var disposition=diagnostics==null || diagnostics.entertainmentDisposition()==null
          ?EntertainmentDisposition.UNCERTAIN:diagnostics.entertainmentDisposition();
      dispositions.merge(disposition,seconds,Long::sum);
      var primary=s.roles().primary();
      String project=primary==null || d!=null && d.fieldConfidence()!=null && d.fieldConfidence().project()<.5?"":names.project(primary.projectCandidate());
      if(!project.isBlank()) {
        projects.merge(project,seconds,Long::sum);
        if(previousEnd!=null && Duration.between(previousEnd,s.start()).getSeconds()<=300
            && !previousProject.isBlank() && !previousProject.equals(project))switches++;
        previousProject=project;previousEnd=end;
      }else if(previousEnd!=null && Duration.between(previousEnd,s.start()).getSeconds()>300) {previousProject="";previousEnd=null;}
      boolean work=WORK.contains(category) && disposition!=EntertainmentDisposition.ENTERTAINMENT;
      boolean fun=disposition==EntertainmentDisposition.ENTERTAINMENT;
      String theme=work?(project.isBlank()?"":project+" の")+categoryLabel(category):"";
      var topics=work?WorkThemeAggregation.topics(primary,s.record()):List.<String>of();
      if(work)themes.add(project,category,topics,s.record(),s.start(),end);
      String leisureLabel=Set.of("social","media","shopping","gaming").contains(category)?categoryLabel(category):"娯楽として分類された閲覧";
      if(fun)leisure.merge(leisureLabel,seconds,Long::sum);
      var visible=new HashSet<String>();
      for(var a:s.record().inference().activities()) {var label=serviceMeaning(a);if(!label.isBlank())visible.add(label);}
      for(var label:visible)services.merge(label,seconds,Long::sum);
      for(int b=0;b<4;b++) {
        var from=date.atTime(b*6,0).atZone(zone).toInstant();
        var until=b==3?date.plusDays(1).atStartOfDay(zone).toInstant():date.atTime((b+1)*6,0).atZone(zone).toInstant();
        long weight=Math.max(0,Duration.between(max(s.start(),from),min(end,until)).getSeconds());
        if(weight==0)continue;
        var count=buckets.computeIfAbsent(BUCKET_ORDER.get(b),k->new Counts());count.observed+=weight;
        count.categories.merge(category,weight,Long::sum);
        if(work)count.workThemes.add(project,category,topics,s.record(),max(s.start(),from),min(end,until));
        addVisible(count.secondary,s.roles().secondary(),weight);addVisible(count.background,s.roles().background(),weight);
      }
      String blockTheme=work?theme:fun?leisureLabel:"";
      String startText=s.start().atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      String endText=end.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      boolean joins=pending!=null && !blockTheme.isBlank() && pending.theme().equals(blockTheme) && pendingWork==work
          && Objects.equals(pendingContinuity,s.record().continuityId())
          && Duration.between(OffsetDateTime.parse(pending.end()).toInstant(),s.start()).getSeconds()<=120;
      if(joins)pending=new Block(pending.start(),endText,pending.observedSeconds()+seconds,blockTheme);
      else {
        if(pending!=null && pending.observedSeconds()>=900)(pendingWork?workBlocks:leisureBlocks).add(pending);
        pending=blockTheme.isBlank()?null:new Block(startText,endText,seconds,blockTheme);
      }
      pendingWork=work;pendingContinuity=s.record().continuityId();
    }
    if(pending!=null && pending.observedSeconds()>=900)(pendingWork?workBlocks:leisureBlocks).add(pending);
    var sections=new LinkedHashMap<String,Bucket>();
    for(var key:BUCKET_ORDER)if(buckets.containsKey(key)) {
      var c=buckets.get(key);
      var dominant=top(c.categories,2).stream().filter(v->!v.name().equals("unknown") && v.seconds()>=c.observed*.15).map(v->categoryLabel(v.name())).toList();
      c.secondary.remove("AI支援");c.background.remove("AI支援");
      var secondary=top(c.secondary,1).stream().filter(v->v.seconds()>=c.observed*.2).map(Weighted::name).filter(v->!dominant.contains(v)).toList();
      var ranked=c.workThemes.ranked(3);
      var workLabels=ranked.stream().map(ProjectThemeStat::label).toList();
      var secondaryThemes=dominant.stream().filter(v->!Set.of("開発","調査","文書作業").contains(v)).limit(1).toList();
      sections.put(key,new Bucket(c.observed,Map.copyOf(c.categories),dominant,secondary,
          top(c.background,1).stream().map(Weighted::name).toList(),workLabels,
          ranked.stream().map(ProjectThemeStat::canonicalProject).filter(v->!v.isBlank()).toList(),secondaryThemes));
    }
    long span=Math.max(0,Duration.between(range.fromInclusive(),range.toExclusive()).getSeconds());
    return new DailySummaryAggregate(date,observed,Math.max(0,span-observed),Map.copyOf(categories),Map.copyOf(dispositions),
        top(projects,5),top(services,3),Collections.unmodifiableMap(sections),themes.ranked(5),services.getOrDefault("AI支援",0L)>=Math.max(600,observed*.2),themes.labels(5),
        top(leisure,3).stream().map(Weighted::name).toList(),blocks(workBlocks),blocks(leisureBlocks),switches,switches>=FREQUENT_SWITCHES,
        observed==0?0:categories.getOrDefault("unknown",0L)/(double)observed,source.size());
  }
  static List<Weighted> top(Map<String,Long> values,int limit) {
    return values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
        .limit(limit).map(e->new Weighted(e.getKey(),e.getValue())).toList();
  }
  private static List<Block> blocks(List<Block> blocks){return blocks.stream().sorted(Comparator.comparingLong(Block::observedSeconds).reversed().thenComparing(Block::start)).limit(3).toList();}
  private static void addVisible(Map<String,Long> target,List<ActivityRecord.Activity> activities,long seconds) {
    activities.stream().map(DailySummaryAggregator::serviceMeaning).filter(s->!s.isBlank()).distinct().forEach(s->target.merge(s,seconds,Long::sum));
  }
  private static String serviceMeaning(ActivityRecord.Activity a) {
    var service=ActivityVocabulary.service(a.service());
    if(Set.of("x","bluesky","mastodon","twitter").contains(service))return "SNS";
    if(Set.of("youtube","youtube-music").contains(service))return "動画・音楽";
    if(Set.of("chatgpt","openai","claude","gemini","deepseek").contains(service))return "AI支援";
    if(service.equals("github") || WORK.contains(ActivityVocabulary.category(a.type())))return "開発・調査";
    return switch(ActivityVocabulary.category(a.type())) {
      case "social"->"SNS";case "media"->"動画・音楽";case "shopping"->"ショッピング";
      case "gaming"->"ゲーム";case "communication"->"コミュニケーション";case "monitoring"->"監視";default->"";
    };
  }
  private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
  private static Instant max(Instant a,Instant b){return a.isAfter(b)?a:b;}
}
